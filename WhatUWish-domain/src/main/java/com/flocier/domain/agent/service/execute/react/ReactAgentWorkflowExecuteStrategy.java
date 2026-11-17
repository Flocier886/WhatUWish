package com.flocier.domain.agent.service.execute.react;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.flocier.domain.agent.model.entity.AutoAgentExecuteResultEntity;
import com.flocier.domain.agent.model.entity.ExecuteCommandEntity;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.summary.SummaryAgent;
import com.flocier.domain.agent.runtime.artifact.ArtifactSelectionResult;
import com.flocier.domain.agent.runtime.memory.MemoryFacade;
import com.flocier.domain.agent.runtime.file.ConversationFileRecord;
import com.flocier.domain.agent.runtime.file.ConversationRunInputRecorder;
import com.flocier.domain.agent.runtime.file.RunWorkspaceService;
import com.flocier.domain.agent.runtime.memory.SessionWorkingMemoryRepository;
import com.flocier.domain.agent.runtime.desktopcontrol.DesktopControlRecord;
import com.flocier.domain.agent.runtime.desktopcontrol.DesktopControlRepository;
import com.flocier.domain.agent.runtime.desktopcontrol.DesktopControlRequiredException;
import com.flocier.domain.agent.runtime.desktopcontrol.DesktopControlStatuses;
import com.flocier.domain.agent.runtime.desktopcontrol.DesktopSessionPort;
import com.flocier.domain.agent.runtime.event.DesktopControlPayload;
import com.flocier.domain.agent.runtime.event.RuntimeEventDraft;
import com.flocier.domain.agent.runtime.event.RuntimeEventPublisher;
import com.flocier.domain.agent.runtime.event.RuntimeEventType;
import com.flocier.domain.agent.runtime.llm.message.LlmMessage;
import com.flocier.domain.agent.runtime.llm.message.LlmMessageRole;
import com.flocier.domain.agent.service.execute.react.step.factory.DefaultReactWorkflowStrategyFactory;
import com.flocier.domain.ledger.service.LedgerFactService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.List;
import java.time.Instant;
import org.springframework.util.StringUtils;
import org.springframework.beans.factory.annotation.Value;

@Slf4j
@Service
public class ReactAgentWorkflowExecuteStrategy {

    @Resource
    private DefaultReactWorkflowStrategyFactory defaultReactWorkflowStrategyFactory;
    @Resource
    private LedgerFactService ledgerFactService;
    @Resource
    private MemoryFacade memoryFacade;
    @Resource
    private SessionWorkingMemoryRepository workingMemoryRepository;
    @Resource
    private DesktopControlRepository desktopControlRepository;
    @Resource
    private DesktopSessionPort desktopSessionPort;
    @Resource
    private RuntimeEventPublisher runtimeEventPublisher;
    @Resource
    private ConversationRunInputRecorder conversationRunInputRecorder;
    @Resource
    private RunWorkspaceService runWorkspaceService;
    @Resource
    private com.flocier.domain.agent.runtime.memory.review.BackgroundSkillReviewService skillReviewService;
    @Value("${memory.working.enabled:true}")
    private boolean workingMemoryEnabled;

    public void execute(ExecuteCommandEntity commandEntity, AgentContext agentContext) throws Exception {
        prepareLedgerContext(commandEntity, agentContext);
        if (workingMemoryEnabled && workingMemoryRepository.hasRequest(agentContext.getRequestId())) {
            throw new IllegalStateException("working memory request already exists: " + agentContext.getRequestId());
        }
        ledgerFactService.recordRunStarted(agentContext, commandEntity.getAiAgentId(), "react_workflow");
        StrategyHandler<ExecuteCommandEntity, DefaultReactWorkflowStrategyFactory.DynamicContext, String> handler =
                defaultReactWorkflowStrategyFactory.workflowStrategyHandler();
        try {
            List<ConversationFileRecord> selectedFiles = agentContext.getAttribute("conversation.selectedFiles");
            // 文件先物化且校验成功，再写入本轮 input 事实；模型看到的路径必须真实可读。
            List<RunWorkspaceService.MaterializedFile> workspaceFiles = runWorkspaceService.prepare(agentContext, selectedFiles);
            if (selectedFiles != null && !selectedFiles.isEmpty()) {
                conversationRunInputRecorder.record(agentContext, selectedFiles);
            }
            // 新会话读 READY 原生消息；尚无投影时把有预算的 Ledger 历史带入首轮。
            List<LlmMessage> working = List.of();
            if (workingMemoryEnabled) {
                working = workingMemoryRepository.loadReadyMessages(commandEntity.getSessionId(), agentContext.getRequestId());
            }
            String resumeControlId = agentContext.getAttribute("desktopControl.resumeId");
            if (StringUtils.hasText(resumeControlId)) {
                DesktopControlRecord resumed = desktopControlRepository.findByControlId(resumeControlId)
                        .orElseThrow(() -> new IllegalStateException("续跑接管记录不存在"));
                if (!commandEntity.getSessionId().equals(resumed.getSessionId()))
                    throw new IllegalStateException("续跑会话不匹配");
                boolean paired = false;
                List<LlmMessage> restored = new java.util.ArrayList<>(working);
                for (int i = restored.size() - 1; i >= 0; i--) {
                    LlmMessage message = restored.get(i);
                    if (message.getRole() == LlmMessageRole.TOOL
                            && resumed.getToolCallId().equals(message.getToolCallId())) {
                        restored.set(i, LlmMessage.builder().role(LlmMessageRole.TOOL)
                                .toolCallId(message.getToolCallId())
                                .content("用户已完成桌面操作。reason=" + resumed.getReason()
                                        + " controlId=" + resumed.getControlId()
                                        + "。请检查操作结果并继续原任务。")
                                .build());
                        paired = true;
                        break;
                    }
                }
                if (!paired) throw new IllegalStateException("续跑缺少已配对的桌面工具观察");
                working = restored;
            }
            if (working.isEmpty()) {
                String history = memoryFacade.buildMemoryPrompt(commandEntity.getSessionId(),
                        (String) agentContext.getAttribute("runId"), agentContext.getRequestId());
                if (StringUtils.hasText(history)) {
                    working = List.of(LlmMessage.builder().role(LlmMessageRole.USER)
                            .content("以下是旧会话的 Ledger 历史记录，仅供参考：\n" + history).build());
                    agentContext.setAttribute("workingMemory.ledgerBootstrap", Boolean.TRUE);
                }
            }
            agentContext.setTask(withFileManifest(commandEntity.getMessage(), workspaceFiles,
                    runWorkspaceService.hasFiles(agentContext)));
            agentContext.setMemoryPrompt("");
            agentContext.setWorkingMemoryMessages(working);

            String result = handler.apply(
                    commandEntity,
                    DefaultReactWorkflowStrategyFactory.DynamicContext.builder()
                            .agentContext(agentContext)
                            .build()
            );
            // 新消息已逐条写入；整条工作流成功时只确认当前活动段。
            if (workingMemoryEnabled && agentContext.getReactMemorySnapshot() != null
                    && !"max_step_reached".equals(agentContext.getFinishReason())
                    && hasReactFinalAnswer(agentContext)) {
                String segmentId = agentContext.getAttribute("workingMemory.segmentId");
                if (StringUtils.hasText(segmentId)) workingMemoryRepository.completeSegment(segmentId);
            }
            ledgerFactService.recordRunFinished(agentContext, "COMPLETED", result, null, null);
            if (agentContext.getPrinter() != null) agentContext.getPrinter().send("result", result);
            if (agentContext.getPrinter() != null) agentContext.getPrinter().send("complete", agentContext.getFinishReason());
            if (agentContext.getResultWriter() != null) {
                agentContext.getResultWriter().write(summaryResult(agentContext, result, commandEntity.getSessionId()));
                agentContext.getResultWriter().write(AutoAgentExecuteResultEntity.createCompleteResult(commandEntity.getSessionId()));
            }
            // 交付完成才尝试后台复盘；调度失败不得进入主流程 FAILED 分支。
            try {
                if (skillReviewService != null) skillReviewService.maybeScheduleAfterSuccess(agentContext);
            } catch (Exception reviewError) {
                log.warn("Skill 复盘调度失败，主结果已成功交付", reviewError);
            }
        } catch (DesktopControlRequiredException waiting) {
            DesktopControlRecord record = new DesktopControlRecord();
            record.setControlId("dc_" + UUID.randomUUID().toString().replace("-", ""));
            record.setSessionId(agentContext.getSessionId());
            record.setOwnerKey("code:" + agentContext.getSessionId());
            record.setSourceRunId(agentContext.getAttribute("runId"));
            record.setSourceRequestId(agentContext.getRequestId());
            record.setToolInvocationId(agentContext.getAttribute("desktopControl.toolInvocationId"));
            record.setToolCallId(waiting.getToolCallId());
            record.setReason(waiting.getReason());
            record.setStreamUrl(waiting.getStreamUrl());
            record.setHoldUntil(waiting.getHoldUntil());
            record.setStatus(DesktopControlStatuses.PENDING);
            record.setAgentId(commandEntity.getAiAgentId());
            record.setMaxStep(commandEntity.getMaxStep());
            try {
                desktopControlRepository.insert(record);
                if (workingMemoryEnabled) {
                    String segmentId = agentContext.getAttribute("workingMemory.segmentId");
                    if (StringUtils.hasText(segmentId)) workingMemoryRepository.completeSegment(segmentId);
                }
                runtimeEventPublisher.publish(new RuntimeEventDraft(RuntimeEventType.DESKTOP_CONTROL,
                        Instant.now(), record.getSessionId(), record.getSourceRunId(),
                        record.getSourceRequestId(), record.getAgentId(), DesktopControlPayload.from(record)));
                ledgerFactService.recordRunFinished(agentContext, "WAITING_USER_INPUT", null, null, null);
            } catch (RuntimeException failure) {
                try { desktopControlRepository.markFailed(record.getControlId()); }
                catch (RuntimeException statusFailure) { log.warn("桌面接管标记失败, controlId={}", record.getControlId(), statusFailure); }
                try { desktopSessionPort.close(record.getSourceRequestId(), record.getSessionId(), record.getOwnerKey()); }
                catch (RuntimeException cleanup) { log.warn("桌面 hold 释放失败, controlId={}", record.getControlId(), cleanup); }
                throw failure;
            }
        } catch (Exception e) {
            ledgerFactService.recordRunFinished(agentContext, "FAILED", null, "react_workflow_execute_failed", e.getMessage());
            throw e;
        } finally {
            try { runWorkspaceService.finish(agentContext); }
            catch (Exception cleanup) { log.warn("Run 工作区清理失败, runId={}", agentContext.getAttribute("runId"), cleanup); }
        }
    }

    /** 把已物化文件的安全清单加入本轮用户消息，不把 locator 或宿主绝对路径交给模型。 */
    private String withFileManifest(String task, List<RunWorkspaceService.MaterializedFile> files, boolean hasWorkspaceFiles) {
        if ((files == null || files.isEmpty()) && !hasWorkspaceFiles) return task;
        if (files == null || files.isEmpty()) return task + "\n\n会话工作区保留了先前的文件；如需继续使用，请先调用 workspace_list 定位。";
        // 沿用 master 的 user_uploaded_files 围栏语义；文件只在本轮用户消息中列出。
        StringBuilder manifest = new StringBuilder(task).append("\n\n<user_uploaded_files>\n")
                .append("本轮明确引用的文件如下（已物化到会话工作区；此前的工作文件可用 workspace_list 查找）：\n");
        for (RunWorkspaceService.MaterializedFile file : files) {
            manifest.append("- fileId=").append(file.fileId())
                    .append("; name=").append(com.alibaba.fastjson.JSON.toJSONString(file.name()))
                    .append("; mime=").append(file.mimeType())
                    .append("; bytes=").append(file.size())
                    .append("; path=").append(file.path()).append('\n');
        }
        return manifest.append("</user_uploaded_files>").toString();
    }

    /**
     * 准备当前执行链所需的账本上下文。
     * 这里会初始化 sessionId、requestId、traceId、runId，并明确区分原始 task 与 memoryPrompt。
     */
    private void prepareLedgerContext(ExecuteCommandEntity commandEntity, AgentContext agentContext) {
        if (agentContext == null) {
            return;
        }
        agentContext.setSessionId(commandEntity.getSessionId());
        if (agentContext.getRequestId() == null || agentContext.getRequestId().isBlank()) {
            agentContext.setRequestId(UUID.randomUUID().toString());
        }
        if (agentContext.getTraceId() == null || agentContext.getTraceId().isBlank()) {
            agentContext.setTraceId(agentContext.getRequestId());
        }
        if (agentContext.getAttribute("runId") == null) {
            agentContext.setAttribute("runId", UUID.randomUUID().toString());
        }
        agentContext.setAttribute("agentId", commandEntity.getAiAgentId());
        agentContext.setQuery(commandEntity.getMessage());
        agentContext.setTask(commandEntity.getMessage());
        agentContext.setMemoryPrompt("");
    }

    private AutoAgentExecuteResultEntity summaryResult(AgentContext context, String summary, String sessionId) {
        ArtifactSelectionResult selection = context.getAttribute(SummaryAgent.FINAL_ARTIFACT_SELECTION_KEY);
        return AutoAgentExecuteResultEntity.createSummaryResult(summary, sessionId, selection);
    }

    /** 步数耗尽或仍等待工具结果的尾消息不算成功的 ReAct 终答。 */
    private boolean hasReactFinalAnswer(AgentContext context) {
        List<LlmMessage> snapshot = context.getReactMemorySnapshot();
        if (snapshot == null || snapshot.isEmpty()) return false;
        LlmMessage last = snapshot.get(snapshot.size() - 1);
        return last.getRole() == LlmMessageRole.ASSISTANT && StringUtils.hasText(last.getContent())
                && (last.getToolCalls() == null || last.getToolCalls().isEmpty());
    }
}
