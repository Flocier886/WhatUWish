package com.flocier.application.agent.execute.workflow;

import com.flocier.application.agent.execute.AgentExecuteCommand;
import com.flocier.application.agent.execute.IExecuteStrategy;
import com.flocier.application.agent.stream.AgentSessionPrinter;
import com.flocier.application.agent.stream.AgentSessionStream;
import com.flocier.application.agent.runtimeevent.RuntimeEventScope;
import com.flocier.application.agent.runtimeevent.RuntimeEventStatusPrinter;
import com.flocier.domain.agent.model.entity.ExecuteCommandEntity;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.printer.Printer;
import com.flocier.domain.agent.runtime.event.RuntimeEventPublisher;
import com.flocier.domain.agent.runtime.stream.AgentResultWriter;
import com.flocier.domain.agent.service.execute.auto.AutoAgentExecuteStrategy;
import com.flocier.domain.agent.service.execute.fixed.FixedAgentExecuteStrategy;
import com.flocier.domain.agent.service.execute.flow.FlowExecuteStrategy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service("workflowAgentExecuteStrategy")
public class WorkflowAgentExecuteStrategy implements IExecuteStrategy {

    @Resource
    private AutoAgentExecuteStrategy autoAgentExecuteStrategy;
    @Resource
    private FlowExecuteStrategy flowExecuteStrategy;
    @Resource
    private FixedAgentExecuteStrategy fixedAgentExecuteStrategy;
    @Resource
    private RuntimeEventPublisher runtimeEventPublisher;

    @Override
    public void execute(AgentExecuteCommand request, AgentSessionStream stream) throws Exception {
        String requestId = request.getRequestId() == null || request.getRequestId().isBlank() ? UUID.randomUUID().toString() : request.getRequestId();
        String runId = request.getRunId() == null || request.getRunId().isBlank() ? UUID.randomUUID().toString() : request.getRunId();
        Printer printer = new RuntimeEventStatusPrinter(new AgentSessionPrinter(stream), runtimeEventPublisher,
                new RuntimeEventScope(request.getSessionId(), runId, requestId, request.getAiAgentId()));
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
        log.info("workflow execute start, agentId={}, sessionId={}, runId={}, requestId={}, traceId={}, strategy={}, maxStep={}",
                request.getAiAgentId(),
                request.getSessionId(),
                runId,
                requestId,
                requestId,
                request.getLegacyStrategy(),
                request.getMaxStep());
        printer.send("status", "workflow started");

        ExecuteCommandEntity commandEntity = ExecuteCommandEntity.builder()
                .aiAgentId(request.getAiAgentId())
                .message(request.getMessage())
                .sessionId(request.getSessionId())
                .maxStep(request.getMaxStep())
                .build();

        try {
            // Run 生命周期由 domain strategy 统一记录，application 入口只负责路由与异常透传。
            String legacyStrategy = request.getLegacyStrategy();
            if ("flowAgentExecuteStrategy".equals(legacyStrategy)) {
                flowExecuteStrategy.execute(commandEntity, null, agentContext);
            } else if ("fixedAgentExecuteStrategy".equals(legacyStrategy)) {
                fixedAgentExecuteStrategy.execute(commandEntity, null, agentContext);
            } else {
                autoAgentExecuteStrategy.execute(commandEntity, null, agentContext);
            }
            log.info("workflow execute finished, agentId={}, sessionId={}, runId={}, requestId={}, status=COMPLETED, resultPreview={}",
                    request.getAiAgentId(),
                    request.getSessionId(),
                    runId,
                    requestId,
                    preview(agentContext.getResult()));
        } catch (Exception e) {
            log.error("workflow execute failed, agentId={}, sessionId={}, runId={}, requestId={}, error={}",
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
