package com.flocier.application.agent.execute.react;

import com.flocier.application.agent.execute.AgentExecuteCommand;
import com.flocier.application.agent.execute.IExecuteStrategy;
import com.flocier.application.agent.stream.AgentSessionStream;
import com.flocier.application.agent.runtimeevent.RuntimeEventScope;
import com.flocier.application.agent.runtimeevent.RuntimeEventStatusPrinter;
import com.flocier.domain.agent.model.entity.ExecuteCommandEntity;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.printer.Printer;
import com.flocier.domain.agent.runtime.event.RuntimeEventPublisher;
import com.flocier.domain.agent.runtime.stream.AgentResultWriter;
import com.flocier.domain.agent.service.execute.react.ReactAgentWorkflowExecuteStrategy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.List;

@Slf4j
@Service("reactAgentExecuteStrategy")
public class ReactAgentExecuteStrategy implements IExecuteStrategy {

    @Resource
    private ReactAgentWorkflowExecuteStrategy reactAgentWorkflowExecuteStrategy;
    @Resource
    private RuntimeEventPublisher runtimeEventPublisher;

    @Override
    public void execute(AgentExecuteCommand request, AgentSessionStream stream) throws Exception {
        String requestId = request.getRequestId() == null || request.getRequestId().isBlank() ? UUID.randomUUID().toString() : request.getRequestId();
        String runId = request.getRunId() == null || request.getRunId().isBlank() ? UUID.randomUUID().toString() : request.getRunId();
        Printer printer = new RuntimeEventStatusPrinter(new com.flocier.application.agent.stream.AgentSessionPrinter(stream),
                runtimeEventPublisher, new RuntimeEventScope(request.getSessionId(), runId, requestId, request.getAiAgentId()));
        AgentContext agentContext = AgentContext.builder()
                .requestId(requestId)
                .sessionId(request.getSessionId())
                .traceId(requestId)
                .query(request.getMessage())
                .task(request.getMessage())
                .maxStep(request.getMaxStep())
                .printer(printer)
                .resultWriter(new StreamAgentResultWriter(stream))
                .build();
        agentContext.setAttribute("runId", runId);
        agentContext.setAttribute("agentId", request.getAiAgentId());
        if (request.getResumeControlId() != null) agentContext.setAttribute("desktopControl.resumeId", request.getResumeControlId());
        if (request.getSelectedFiles() != null && !request.getSelectedFiles().isEmpty()) {
            agentContext.setAttribute("conversation.selectedFiles", List.copyOf(request.getSelectedFiles()));
        }
        log.info("react execute start, agentId={}, sessionId={}, runId={}, requestId={}, traceId={}, maxStep={}",
                request.getAiAgentId(),
                request.getSessionId(),
                runId,
                requestId,
                requestId,
                request.getMaxStep());

        ExecuteCommandEntity commandEntity = ExecuteCommandEntity.builder()
                .aiAgentId(request.getAiAgentId())
                .message(request.getMessage())
                .sessionId(request.getSessionId())
                .maxStep(request.getMaxStep())
                .build();
        try {
            // Run 生命周期由 domain workflow 统一记录，application 入口只负责上下文适配与异常透传。
            reactAgentWorkflowExecuteStrategy.execute(commandEntity, agentContext);
            log.info("react execute finished, agentId={}, sessionId={}, runId={}, requestId={}, status=COMPLETED, resultPreview={}",
                    request.getAiAgentId(),
                    request.getSessionId(),
                    runId,
                    requestId,
                    preview(agentContext.getResult()));
        } catch (Exception e) {
            log.error("react execute failed, agentId={}, sessionId={}, runId={}, requestId={}, error={}",
                    request.getAiAgentId(),
                    request.getSessionId(),
                    runId,
                    requestId,
                    e.getMessage(),
                    e);
            throw e;
        }
    }

    private String preview(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200) + "...";
    }

    private static class StreamAgentResultWriter implements AgentResultWriter {
        private final AgentSessionStream stream;

        private StreamAgentResultWriter(AgentSessionStream stream) {
            this.stream = stream;
        }

        @Override
        public void write(Object object) {
            try {
                stream.send("message", object);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
