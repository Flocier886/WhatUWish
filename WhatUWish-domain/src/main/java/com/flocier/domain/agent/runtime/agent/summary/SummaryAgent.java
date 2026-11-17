package com.flocier.domain.agent.runtime.agent.summary;

import com.flocier.domain.agent.reactor.config.ReactorConfig;
import com.flocier.domain.agent.runtime.RuntimeDependencies;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.react.ReActAgent;
import com.flocier.domain.agent.runtime.event.RuntimeEventToolPresentation;
import com.flocier.domain.agent.runtime.agent.react.prompt.ReactPromptTemplateRenderer;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeFormatter;
import com.flocier.domain.agent.runtime.artifact.ArtifactSelectionParser;
import com.flocier.domain.agent.runtime.artifact.ArtifactSelectionResult;
import com.flocier.domain.agent.runtime.llm.LLM;
import com.flocier.domain.agent.runtime.llm.message.LlmMessage;
import com.flocier.domain.agent.runtime.llm.message.LlmMessageRole;
import com.flocier.domain.agent.runtime.llm.message.LlmRequest;
import com.flocier.domain.agent.runtime.llm.response.LlmResponse;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class SummaryAgent extends AbstractSummaryAgent {

    public static final String FINAL_SUMMARY_KEY = "summary.final";
    public static final String FINAL_ARTIFACT_SELECTION_KEY = "summary.artifactSelection";

    @Resource
    private LLM llm;
    @Resource
    private RuntimeDependencies runtimeDependencies;
    @Resource
    private ReactPromptTemplateRenderer promptTemplateRenderer;
    @Resource
    private ArtifactRuntimeFormatter artifactRuntimeFormatter;
    @Resource
    private ArtifactSelectionParser artifactSelectionParser;

    @Override
    public String run(AgentContext context) {
        ReactorConfig reactorConfig = runtimeDependencies.reactorConfig();
        String prompt = promptTemplateRenderer.render(reactorConfig.resolveSummaryPrompt(), buildVariables(context));

        List<LlmMessage> summaryMessages = new ArrayList<>(context.getMemory());
        summaryMessages.add(LlmMessage.builder()
                .role(LlmMessageRole.SYSTEM)
                .content(prompt)
                .build());
        String artifactContext = artifactRuntimeFormatter.formatSummaryContext(
                context.requireArtifactRuntimeRegistry().listDeliverableOutputs());
        if (StringUtils.hasText(artifactContext)) {
            summaryMessages.add(LlmMessage.builder().role(LlmMessageRole.SYSTEM).content(artifactContext).build());
        }

        LlmResponse response = llm.call(LlmRequest.builder()
                .model(resolveModel(reactorConfig))
                .temperature(reactorConfig.getSummaryTemperature())
                .messages(summaryMessages)
                .attributes(buildLedgerAttributes(context))
                .build());

        ArtifactSelectionResult selection = artifactSelectionParser.parse(
                fallbackResult(response.getText()), context.requireArtifactRuntimeRegistry());
        String finalSummary = RuntimeEventToolPresentation.summary(selection.getSummaryText());
        context.setAttribute(FINAL_SUMMARY_KEY, finalSummary);
        context.setAttribute(FINAL_ARTIFACT_SELECTION_KEY, selection);
        context.setResult(finalSummary);
        context.appendMemory(LlmMessage.builder()
                .role(LlmMessageRole.ASSISTANT)
                .content(finalSummary)
                .build());
        return finalSummary;
    }

    private String resolveModel(ReactorConfig reactorConfig) {
        if (StringUtils.hasText(reactorConfig.getSummaryModelName())) {
            return reactorConfig.getSummaryModelName();
        }
        return reactorConfig.getReactModelName();
    }

    private Map<String, String> buildVariables(AgentContext context) {
        Map<String, String> variables = new HashMap<>();
        variables.put("task", safe(context.getTask()));
        variables.put("memory_prompt", safe(context.getMemoryPrompt()));
        variables.put("query", safe(context.getQuery()));
        variables.put("react_result", safe(context.getAttribute(ReActAgent.REACT_RESULT_KEY)));
        variables.put("react_trace", traceAsText(context));
        variables.put("tool_observation", toolObservationText(context));
        variables.put("memory", memoryAsText(context));
        variables.put("artifact_context", artifactRuntimeFormatter.formatSummaryContext(
                context.requireArtifactRuntimeRegistry().listDeliverableOutputs()));
        return variables;
    }

    @SuppressWarnings("unchecked")
    private String traceAsText(AgentContext context) {
        List<String> trace = context.getAttribute(ReActAgent.REACT_TRACE_KEY);
        if (trace == null || trace.isEmpty()) {
            return "";
        }
        return String.join(" | ", trace);
    }

    private String toolObservationText(AgentContext context) {
        if (context.getToolExecutionResults() == null || context.getToolExecutionResults().isEmpty()) {
            return "";
        }
        return context.getToolExecutionResults().stream()
                .map(item -> item.getToolName() + ": " + safe(item.getOutputText()))
                .collect(Collectors.joining("\n"));
    }

    private String memoryAsText(AgentContext context) {
        return context.getMemory().stream()
                .map(message -> "[" + message.getRole() + "] " + safe(message.getContent()))
                .collect(Collectors.joining("\n"));
    }

    private String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Map<String, Object> buildLedgerAttributes(AgentContext context) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("scene", "summary");
        putIfPresent(attributes, "agentId", context.getAttribute("agentId"));
        putIfPresent(attributes, "runId", context.getAttribute("runId"));
        attributes.put("requestId", context.getRequestId());
        attributes.put("sessionId", context.getSessionId());
        attributes.put("traceId", StringUtils.hasText(context.getTraceId()) ? context.getTraceId() : context.getRequestId());
        attributes.put("step", context.getCurrentStep());
        return attributes;
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
