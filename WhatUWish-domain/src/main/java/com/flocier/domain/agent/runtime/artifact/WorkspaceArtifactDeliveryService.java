package com.flocier.domain.agent.runtime.artifact;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolExecutionResult;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentLocator;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentRemover;
import com.flocier.domain.ledger.adapter.repository.IArtifactLedgerRepository;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import com.flocier.domain.ledger.model.entity.ToolInvocationLedgerEntity;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 材料工具与代码分析 Rich Tool 共用的工作区文件登记和失败补偿。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceArtifactDeliveryService {
    private final ArtifactRuntimeCollector collector;
    private final ArtifactRuntimeRegistrationService registration;
    private final IArtifactLedgerRepository repository;
    private final ArtifactContentRemover remover;

    public void register(AgentContext context, ToolInvokeRequest request, ToolExecutionResult result,
                         ToolInvocationLedgerEntity invocation) {
        if (result.getArtifactDescriptors() == null || result.getArtifactDescriptors().isEmpty()) return;
        try {
            if (invocation == null) throw new IllegalStateException("材料产物缺少可信工具调用");
            ArtifactRuntimeSource source = ArtifactRuntimeSource.from(context, request, result.getToolName());
            var descriptors = collector.collect(request, result, context);
            if (descriptors.size() != result.getArtifactDescriptors().size())
                throw new IllegalStateException("材料产物描述不完整");
            var registered = registration.registerBatchAndPublish(context, invocation, source, descriptors);
            if (registered.size() != descriptors.size())
                throw new IllegalStateException("材料产物未全部登记");
            if ("code_execution".equals(result.getToolName()) || "code_interpreter".equals(result.getToolName())) {
                List<String> ids = registered.stream().map(item -> item.getArtifact().getArtifactId()).toList();
                result.setArtifactRefs(ids);
                String prefix = result.getLlmObservation() == null ? "" : result.getLlmObservation();
                result.setLlmObservation(prefix + "\n已登记" + (result.isSuccess() ? "" : "的部分")
                        + "产物 Artifact ID：" + String.join("、", ids));
            }
        } catch (RuntimeException failure) {
            log.warn("workspace artifact registration failed, toolCallId={}", request.getToolCallId(), failure);
            cleanupUnregistered(result.getArtifactDescriptors(), context, request);
            result.setSuccess(false);
            result.setStatus("failed");
            result.setErrorCode("material_artifact_registration_failed");
            result.setErrorMessage("材料产物登记失败");
            result.setOutputText("材料解析已完成，但产物登记失败；本次结果不可作为已交付文件使用。");
            result.setLlmObservation("材料解析已完成，但产物登记失败；本次 Artifact ID 不可使用，请重试。");
            result.setStructuredOutput(Map.of("errorCode", "material_artifact_registration_failed"));
            result.setArtifactDescriptors(List.of());
            result.setArtifactRefs(List.of());
            context.requireArtifactRuntimeRegistry().removeByToolCallId(request.getToolCallId());
        }
    }

    private void cleanupUnregistered(List<Map<String, Object>> descriptors,
                                     AgentContext context, ToolInvokeRequest request) {
        if (descriptors == null) return;
        String runId = context == null ? null : context.getAttribute("runId");
        String toolCallId = request == null ? null : request.getToolCallId();
        List<ArtifactLedgerEntity> committed;
        try {
            committed = runId == null || toolCallId == null ? List.of()
                    : repository.findByRunIdAndToolCallId(runId, toolCallId);
        } catch (RuntimeException lookupFailure) {
            log.warn("workspace artifact compensation skipped after ledger lookup failure", lookupFailure);
            return;
        }
        for (Map<String, Object> descriptor : descriptors) {
            if (descriptor == null) continue;
            Object id = descriptor.get("artifactId");
            Object type = descriptor.get("storageType");
            Object key = descriptor.get("storageKey");
            if (!(id instanceof String artifactId) || artifactId.isBlank()
                    || !(type instanceof String storageType) || storageType.isBlank()
                    || !(key instanceof String storageKey) || storageKey.isBlank()) continue;
            try {
                if (repository.findByArtifactId(artifactId).isPresent()
                        || committed.stream().anyMatch(item -> storageType.equals(item.getStorageType())
                        && storageKey.equals(item.getStorageKey()))) continue;
                remover.remove(new ArtifactContentLocator(storageType, storageKey, null));
            } catch (RuntimeException cleanupFailure) {
                log.warn("workspace artifact compensation skipped, artifactId={}", artifactId, cleanupFailure);
            }
        }
    }
}
