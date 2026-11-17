package com.flocier.domain.agent.runtime.skill;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.BaseTool;
import com.flocier.domain.agent.runtime.agent.tool.ToolCollection;
import com.flocier.domain.agent.runtime.agent.tool.ToolExecutionResult;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolRunner;
import com.flocier.domain.agent.runtime.llm.LLM;
import com.flocier.domain.agent.runtime.llm.message.LlmMessage;
import com.flocier.domain.agent.runtime.llm.message.LlmMessageRole;
import com.flocier.domain.agent.runtime.llm.message.ToolCall;
import com.flocier.domain.agent.runtime.llm.toolask.ToolAskRequest;
import com.flocier.domain.agent.runtime.llm.toolask.ToolAskResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 兼容用 Skill 子空间执行器。
 *
 * <p>普通 skill 使用默认走 master 风格工具链；本类只保留旧 `skill_execute_tool`
 * 的受限子链路能力，不再承载注册脚本发现、脚本 action 或额外结果模型。</p>
 */
@Slf4j
@Component
public class SkillExecutor {

    private final SkillRegistry skillRegistry;
    private final SkillRuntimeOptions skillRuntimeOptions;
    private final LLM llm;
    private final ToolRunner toolRunner;

    public SkillExecutor(SkillRegistry skillRegistry,
                         SkillRuntimeOptions skillRuntimeOptions,
                         LLM llm,
                         ToolRunner toolRunner) {
        this.skillRegistry = skillRegistry;
        this.skillRuntimeOptions = skillRuntimeOptions;
        this.llm = llm;
        this.toolRunner = toolRunner;
    }

    public SkillExecutionResult execute(String skillName,
                                        Map<String, Object> arguments,
                                        AgentContext parentContext,
                                        String parentToolCallId) {
        SkillDefinition definition = skillRegistry.getRequiredSkill(skillName);
        ToolCollection availableTools = buildAllowedToolCollection(definition, parentContext);
        SkillExecutionContext executionContext = buildExecutionContext(definition, arguments, parentContext, parentToolCallId);
        AgentContext skillAgentContext = buildChildAgentContext(parentContext, executionContext, availableTools);

        List<String> usedTools = new ArrayList<>();
        List<String> artifactRefs = new ArrayList<>();
        String lastObservation = "";
        String lastThought = "";
        String finalAnswer = "";

        int maxSteps = skillRuntimeOptions.getMaxExecutionSteps() <= 0 ? 3 : skillRuntimeOptions.getMaxExecutionSteps();
        for (int step = 1; step <= maxSteps; step++) {
            skillAgentContext.setCurrentStep(step);
            ToolAskResponse thinkResponse = llm.toolAsk(ToolAskRequest.builder()
                    .model(resolveModel(parentContext))
                    .currentStep(step)
                    .maxStep(maxSteps)
                    .temperature(0.2)
                    .toolDescriptionText(availableTools.describeTools())
                    .toolDefinitions(availableTools.exportToolDefinitions())
                    .messages(buildMessages(definition, executionContext, skillAgentContext, lastThought, lastObservation))
                    .attributes(buildLlmAttributes(executionContext, step))
                    .build());

            lastThought = thinkResponse == null ? "" : safe(thinkResponse.getThought());
            skillAgentContext.appendMemory(LlmMessage.builder()
                    .role(LlmMessageRole.ASSISTANT)
                    .content(lastThought)
                    .toolCalls(thinkResponse == null || thinkResponse.getToolCalls() == null ? List.of() : thinkResponse.getToolCalls())
                    .build());

            if (thinkResponse == null || thinkResponse.isCompleted() || thinkResponse.getToolCalls() == null || thinkResponse.getToolCalls().isEmpty()) {
                executionContext.setState("completed");
                finalAnswer = lastThought;
                return buildSuccessResult(definition, executionContext, finalAnswer, usedTools, artifactRefs);
            }

            StringBuilder observation = new StringBuilder();
            for (ToolCall toolCall : thinkResponse.getToolCalls()) {
                if (toolCall == null || toolCall.getFunction() == null) {
                    continue;
                }
                usedTools.add(toolCall.getFunction().getName());
                executionContext.getUsedTools().add(toolCall.getFunction().getName());
                ToolExecutionResult toolResult = toolRunner.run(
                        availableTools,
                        ToolInvokeRequest.builder()
                                .toolName(toolCall.getFunction().getName())
                                .toolCallId(toolCall.getId())
                                .arguments(parseArguments(toolCall.getFunction().getArguments()))
                                .requestId(executionContext.getRequestId())
                                .sessionId(executionContext.getSessionId())
                                .step(step)
                                .metadata(Map.of(
                                        "skillName", definition.getName(),
                                        "skillExecutionId", executionContext.getSkillExecutionId(),
                                        "parentToolCallId", executionContext.getParentToolCallId()
                                ))
                                .build(),
                        skillAgentContext
                );
                if (toolResult.getArtifactRefs() != null) {
                    artifactRefs.addAll(toolResult.getArtifactRefs());
                    executionContext.getGeneratedArtifacts().addAll(toolResult.getArtifactRefs());
                }
                String toolText = StringUtils.hasText(toolResult.getLlmObservation())
                        ? toolResult.getLlmObservation()
                        : toolResult.getPreferredObservationText();
                if (!observation.isEmpty()) {
                    observation.append("\n");
                }
                observation.append(toolResult.getToolName()).append(": ").append(toolText);
                skillAgentContext.appendMemory(LlmMessage.builder()
                        .role(LlmMessageRole.TOOL)
                        .toolCallId(toolCall.getId())
                        .content(toolText)
                        .build());
                executionContext.getIntermediateResults().add(toolResult.getToolName() + ": " + toolText);
            }
            lastObservation = observation.toString();
        }

        executionContext.setState("max_step_reached");
        finalAnswer = StringUtils.hasText(lastThought) ? lastThought : lastObservation;
        return SkillExecutionResult.builder()
                .success(false)
                .status("failed")
                .errorCode("skill_max_steps_reached")
                .errorMessage("Skill reached max execution steps without completion")
                .finalAnswer(finalAnswer)
                .llmObservation(finalAnswer)
                .summary(buildSummary(definition, usedTools, finalAnswer))
                .structuredOutput(buildStructuredOutput(definition, executionContext, usedTools, artifactRefs, finalAnswer))
                .ledgerFacts(buildLedgerFacts(definition, executionContext, usedTools, artifactRefs, finalAnswer, false))
                .artifactRefs(artifactRefs)
                .usedTools(usedTools)
                .skillExecutionId(executionContext.getSkillExecutionId())
                .skillName(definition.getName())
                .skillResultSummary(finalAnswer)
                .build();
    }

    private SkillExecutionResult buildSuccessResult(SkillDefinition definition,
                                                    SkillExecutionContext executionContext,
                                                    String finalAnswer,
                                                    List<String> usedTools,
                                                    List<String> artifactRefs) {
        return SkillExecutionResult.builder()
                .success(true)
                .status("success")
                .finalAnswer(finalAnswer)
                .llmObservation(finalAnswer)
                .summary(buildSummary(definition, usedTools, finalAnswer))
                .structuredOutput(buildStructuredOutput(definition, executionContext, usedTools, artifactRefs, finalAnswer))
                .ledgerFacts(buildLedgerFacts(definition, executionContext, usedTools, artifactRefs, finalAnswer, true))
                .artifactRefs(artifactRefs)
                .usedTools(usedTools)
                .skillExecutionId(executionContext.getSkillExecutionId())
                .skillName(definition.getName())
                .skillResultSummary(finalAnswer)
                .build();
    }

    private List<Map<String, Object>> buildLedgerFacts(SkillDefinition definition,
                                                       SkillExecutionContext executionContext,
                                                       List<String> usedTools,
                                                       List<String> artifactRefs,
                                                       String finalAnswer,
                                                       boolean success) {
        Map<String, Object> fact = new LinkedHashMap<>();
        fact.put("factType", "skill_execution");
        fact.put("skillName", definition.getName());
        fact.put("skillExecutionId", executionContext.getSkillExecutionId());
        fact.put("status", success ? "success" : "failed");
        fact.put("usedTools", new ArrayList<>(usedTools));
        fact.put("artifactRefs", new ArrayList<>(artifactRefs));
        fact.put("finalAnswer", finalAnswer);
        return List.of(fact);
    }

    private Map<String, Object> buildStructuredOutput(SkillDefinition definition,
                                                      SkillExecutionContext executionContext,
                                                      List<String> usedTools,
                                                      List<String> artifactRefs,
                                                      String finalAnswer) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("skillName", definition.getName());
        output.put("skillExecutionId", executionContext.getSkillExecutionId());
        output.put("usedTools", usedTools);
        output.put("artifactRefs", artifactRefs);
        output.put("finalAnswer", finalAnswer);
        return output;
    }

    private String buildSummary(SkillDefinition definition, List<String> usedTools, String finalAnswer) {
        return definition.getName() + " executed with tools [" + String.join(", ", usedTools) + "], answer=" + shorten(finalAnswer);
    }

    private Map<String, Object> buildLlmAttributes(SkillExecutionContext executionContext, int step) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("mode", "skill_tool_ask");
        attributes.put("scene", "skill_execute:" + executionContext.getSkillName());
        attributes.put("requestId", executionContext.getRequestId());
        attributes.put("sessionId", executionContext.getSessionId());
        attributes.put("traceId", executionContext.getTraceId());
        attributes.put("runId", executionContext.getRunId());
        attributes.put("step", step);
        attributes.put("skillName", executionContext.getSkillName());
        attributes.put("skillExecutionId", executionContext.getSkillExecutionId());
        return attributes;
    }

    private List<LlmMessage> buildMessages(SkillDefinition definition,
                                           SkillExecutionContext executionContext,
                                           AgentContext childContext,
                                           String lastThought,
                                           String lastObservation) {
        List<LlmMessage> messages = new ArrayList<>(childContext.getMemory());
        messages.add(LlmMessage.builder()
                .role(LlmMessageRole.SYSTEM)
                .content(buildSystemPrompt(definition, executionContext, childContext, lastThought, lastObservation))
                .build());
        return messages;
    }

    private String buildSystemPrompt(SkillDefinition definition,
                                     SkillExecutionContext executionContext,
                                     AgentContext childContext,
                                     String lastThought,
                                     String lastObservation) {
        String template = definition.getPromptTemplates().get("system");
        StringBuilder prompt = new StringBuilder();
        if (StringUtils.hasText(template)) {
            prompt.append(template.trim()).append("\n\n");
        }
        prompt.append("你正在执行一个已注册的 Skill。\n")
                .append("skill_name: ").append(definition.getName()).append("\n")
                .append("skill_title: ").append(safe(definition.getTitle())).append("\n")
                .append("skill_description: ").append(safe(definition.getDescription())).append("\n")
                .append("skill_scenario: ").append(safe(definition.getScenario())).append("\n")
                .append("task: ").append(safe(executionContext.getTask())).append("\n")
                .append("arguments: ").append(JSON.toJSONString(executionContext.getInput())).append("\n")
                .append("current_step: ").append(childContext.getCurrentStep()).append("/")
                .append(skillRuntimeOptions.getMaxExecutionSteps()).append("\n")
                .append("allowed_tools: ").append(String.join(", ", definition.getToolRequirements())).append("\n");
        if (!definition.getConstraints().isEmpty()) {
            prompt.append("constraints:\n");
            for (String constraint : definition.getConstraints()) {
                prompt.append("- ").append(constraint).append("\n");
            }
        }
        if (StringUtils.hasText(lastThought)) {
            prompt.append("last_thought: ").append(lastThought).append("\n");
        }
        if (StringUtils.hasText(lastObservation)) {
            prompt.append("last_observation: ").append(lastObservation).append("\n");
        }
        prompt.append("如果任务已完成，直接输出中文最终结论，不要再调用工具。\n")
                .append("如果任务未完成，请仅通过原生 tool calling 选择下一步工具。\n");
        return prompt.toString();
    }

    private ToolCollection buildAllowedToolCollection(SkillDefinition definition, AgentContext parentContext) {
        if (parentContext == null || parentContext.getToolCollection() == null) {
            throw new IllegalStateException("Parent tool collection is required for skill execution");
        }
        List<String> allowedNames = definition.getToolRequirements();
        List<BaseTool> tools = parentContext.getToolCollection().listTools().stream()
                .filter(tool -> !isSkillEntrypoint(tool.name()))
                .filter(tool -> allowedNames.isEmpty() || allowedNames.contains(tool.name()))
                .collect(Collectors.toList());
        if (tools.isEmpty()) {
            throw new IllegalStateException("No allowed tools found for skill: " + definition.getName());
        }
        return new ToolCollection(tools);
    }

    private boolean isSkillEntrypoint(String toolName) {
        return "skill_view".equals(toolName)
                || "skills_search".equals(toolName)
                || "skill_tool".equals(toolName)
                || "skill_read_tool".equals(toolName)
                || "skill_list_directory_tool".equals(toolName)
                || "skill_glob_tool".equals(toolName)
                || "skill_grep_tool".equals(toolName)
                || "skill_script_runner_tool".equals(toolName)
                || "skill_execute_tool".equals(toolName);
    }

    private SkillExecutionContext buildExecutionContext(SkillDefinition definition,
                                                        Map<String, Object> arguments,
                                                        AgentContext parentContext,
                                                        String parentToolCallId) {
        String task = extractTask(arguments, parentContext, definition);
        return SkillExecutionContext.builder()
                .skillExecutionId(UUID.randomUUID().toString())
                .skillId(definition.getSkillId())
                .skillName(definition.getName())
                .sessionId(parentContext == null ? null : parentContext.getSessionId())
                .runId(parentContext == null ? null : parentContext.getAttribute("runId"))
                .requestId(parentContext == null ? null : parentContext.getRequestId())
                .traceId(parentContext == null ? null : parentContext.getTraceId())
                .parentToolCallId(parentToolCallId)
                .task(task)
                .input(arguments == null ? new LinkedHashMap<>() : new LinkedHashMap<>(arguments))
                .state("running")
                .build();
    }

    private AgentContext buildChildAgentContext(AgentContext parentContext,
                                                SkillExecutionContext executionContext,
                                                ToolCollection toolCollection) {
        AgentContext child = AgentContext.builder()
                .requestId(executionContext.getRequestId())
                .sessionId(executionContext.getSessionId())
                .traceId(executionContext.getTraceId())
                .query(executionContext.getTask())
                .task(executionContext.getTask())
                .maxStep(skillRuntimeOptions.getMaxExecutionSteps())
                .currentStep(1)
                .toolCollection(toolCollection)
                .memory(new ArrayList<>())
                .lastToolCalls(new ArrayList<>())
                .toolExecutionResults(new ArrayList<>())
                .attributes(new LinkedHashMap<>())
                .build();
        if (parentContext != null && parentContext.getAttributes() != null) {
            child.getAttributes().putAll(parentContext.getAttributes());
        }
        child.setAttribute("runId", executionContext.getRunId());
        child.setAttribute("skillName", executionContext.getSkillName());
        child.setAttribute("skillExecutionId", executionContext.getSkillExecutionId());
        child.setAttribute("parentToolCallId", executionContext.getParentToolCallId());
        child.appendMemory(LlmMessage.builder()
                .role(LlmMessageRole.USER)
                .content(executionContext.getTask())
                .build());
        return child;
    }

    private String extractTask(Map<String, Object> arguments, AgentContext parentContext, SkillDefinition definition) {
        if (arguments != null) {
            Object value = arguments.get("task");
            if (value != null && StringUtils.hasText(String.valueOf(value))) {
                return String.valueOf(value);
            }
        }
        if (parentContext != null && StringUtils.hasText(parentContext.getQuery())) {
            return parentContext.getQuery();
        }
        return safe(definition.getDescription());
    }

    private String resolveModel(AgentContext parentContext) {
        if (parentContext == null) {
            return null;
        }
        Object explicit = parentContext.getAttribute("reactModel");
        if (explicit != null && StringUtils.hasText(String.valueOf(explicit))) {
            return String.valueOf(explicit);
        }
        Object fallback = parentContext.getAttribute("llmModel");
        return fallback == null ? null : String.valueOf(fallback);
    }

    private Map<String, Object> parseArguments(String arguments) {
        if (!StringUtils.hasText(arguments)) {
            return Map.of();
        }
        try {
            return JSON.parseObject(arguments, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("parse skill tool arguments failed, arguments={}", arguments, e);
            return Map.of();
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String shorten(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200) + "...";
    }
}
