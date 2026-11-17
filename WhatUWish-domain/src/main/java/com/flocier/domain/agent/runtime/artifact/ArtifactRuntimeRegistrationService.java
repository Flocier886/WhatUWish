package com.flocier.domain.agent.runtime.artifact;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolExecutionResult;
import com.flocier.domain.agent.runtime.event.RuntimeEventMapper;
import com.flocier.domain.agent.runtime.event.RuntimeEventPublisher;
import com.flocier.domain.ledger.adapter.repository.IArtifactLedgerRepository;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import com.flocier.domain.ledger.model.entity.ToolInvocationLedgerEntity;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.List;

/**
 * 运行中正式 Artifact 的统一登记入口。
 * Rich Tool 只负责把已校验的协议输入映射为 descriptor，不直接操作 Artifact Ledger 或 Runtime Event。
 */
@Service
@RequiredArgsConstructor
public class ArtifactRuntimeRegistrationService {

    private final ArtifactRuntimeLedgerRecorder ledgerRecorder;
    private final IArtifactLedgerRepository artifactRepository;
    private final RuntimeEventMapper runtimeEventMapper;
    private final RuntimeEventPublisher runtimeEventPublisher;

    @Transactional
    public ArtifactRuntimeRegistrationResult register(ArtifactRuntimeRegistrationRequest request) {
        validate(request);
        ToolInvocationLedgerEntity invocation = request.getToolInvocation();
        ArtifactRuntimeSource source = request.getSource();
        ArtifactRuntimeDescriptor descriptor = request.getDescriptor();
        String idempotencyKey = ledgerRecorder.idempotencyKeyFor(source, descriptor);
        ArtifactLedgerEntity existing = artifactRepository.findByIdempotencyKey(idempotencyKey).orElse(null);

        ArtifactLedgerEntity artifact = existing;
        if (artifact == null) {
            artifact = ledgerRecorder.recordBinding(invocation, runtimeResult(invocation),
                    ArtifactRuntimeBinding.builder().source(source).descriptor(descriptor).build());
        }
        if (artifact == null) {
            throw new IllegalStateException("Artifact 运行中登记未通过统一账本准入");
        }

        AgentContext context = request.getContext();
        context.requireArtifactRuntimeRegistry().register(source, descriptor);
        return new ArtifactRuntimeRegistrationResult(artifact, existing == null);
    }

    /** 由 Artifact 模块拥有 Artifact 事件映射和提交后发布；调用方只决定与其他已持久化事实的顺序。 */
    public void publishCreatedAfterCommit(ArtifactRuntimeRegistrationResult result, AgentContext context) {
        if (result == null || !result.isCreated() || result.getArtifact() == null) {
            return;
        }
        runtimeEventPublisher.publishAfterCommit(
                runtimeEventMapper.artifactCreated(result.getArtifact(), agentId(context))
        );
    }

    public ArtifactRuntimeRegistrationResult registerAndPublish(ArtifactRuntimeRegistrationRequest request) {
        ArtifactRuntimeRegistrationResult result = register(request);
        publishCreatedAfterCommit(result, request.getContext());
        return result;
    }

    /** 材料工具一次可能生成多个文件；先整批校验，数据库事务失败时回滚并延后发布事件。 */
    @Transactional
    public List<ArtifactRuntimeRegistrationResult> registerBatchAndPublish(
            AgentContext context, ToolInvocationLedgerEntity invocation,
            ArtifactRuntimeSource source, List<ArtifactRuntimeDescriptor> descriptors) {
        List<ArtifactRuntimeRegistrationResult> results = new ArrayList<>();
        // 先校验整批描述，再写入第一条账本，避免尾部无效对象造成部分登记。
        for (ArtifactRuntimeDescriptor descriptor : descriptors) {
            validate(ArtifactRuntimeRegistrationRequest.builder()
                    .context(context).toolInvocation(invocation).source(source).descriptor(descriptor).build());
        }
        for (ArtifactRuntimeDescriptor descriptor : descriptors) {
            String key = ledgerRecorder.idempotencyKeyFor(source, descriptor);
            ArtifactLedgerEntity existing = artifactRepository.findByIdempotencyKey(key).orElse(null);
            ArtifactLedgerEntity artifact = existing == null
                    ? ledgerRecorder.recordBinding(invocation, runtimeResult(invocation),
                    ArtifactRuntimeBinding.builder().source(source).descriptor(descriptor).build())
                    : existing;
            if (artifact == null) throw new IllegalStateException("材料产物账本登记失败");
            results.add(new ArtifactRuntimeRegistrationResult(artifact, existing == null));
        }
        // 批次账本写完后才更新运行期 Registry；数据库提交失败仍需调用方撤回声明。
        for (ArtifactRuntimeDescriptor descriptor : descriptors) context.requireArtifactRuntimeRegistry().register(source, descriptor);
        for (ArtifactRuntimeRegistrationResult result : results) publishCreatedAfterCommit(result, context);
        return List.copyOf(results);
    }

    private void validate(ArtifactRuntimeRegistrationRequest request) {
        if (request == null || request.getContext() == null || request.getToolInvocation() == null
                || request.getSource() == null || request.getDescriptor() == null) {
            throw new IllegalArgumentException("Artifact 运行中登记上下文不完整");
        }
        ToolInvocationLedgerEntity invocation = request.getToolInvocation();
        ArtifactRuntimeSource source = request.getSource();
        ArtifactRuntimeDescriptor descriptor = request.getDescriptor();
        if (StringUtils.isAnyBlank(invocation.getToolInvocationId(), invocation.getRunId(), invocation.getToolCallId())
                || !descriptor.hasStableLocation()
                || StringUtils.isBlank(descriptor.getArtifactId())
                || !StringUtils.equals(invocation.getToolCallId(), source.getToolCallId())) {
            throw new IllegalArgumentException("Artifact 运行中登记缺少稳定身份或与 Tool Invocation 不一致");
        }
    }

    private ToolExecutionResult runtimeResult(ToolInvocationLedgerEntity invocation) {
        return ToolExecutionResult.builder().toolName(invocation.getToolName()).build();
    }

    private String agentId(AgentContext context) {
        Object value = context.getAttribute("agentId");
        return value == null ? null : String.valueOf(value);
    }
}
