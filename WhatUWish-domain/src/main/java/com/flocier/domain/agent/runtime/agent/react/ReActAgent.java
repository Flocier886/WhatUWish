package com.flocier.domain.agent.runtime.agent.react;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.flocier.domain.agent.reactor.config.ReactorConfig;
import com.flocier.domain.agent.runtime.RuntimeDependencies;
import com.flocier.domain.agent.runtime.agent.AbstractThinkActAgent;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.AgentRunMode;
import com.flocier.domain.agent.runtime.agent.react.plan.ReactThinkResult;
import com.flocier.domain.agent.runtime.agent.react.prompt.ReactPromptTemplateRenderer;
import com.flocier.domain.agent.runtime.agent.tool.ToolCollection;
import com.flocier.domain.agent.runtime.agent.tool.ToolCollectionFactory;
import com.flocier.domain.agent.runtime.agent.tool.ToolExecutionResult;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.PreparedToolExecution;
import com.flocier.domain.agent.runtime.agent.tool.ToolRunner;
import com.flocier.domain.agent.runtime.agent.tool.impl.RequestDesktopControlTool;
import com.flocier.domain.agent.runtime.agent.tool.impl.SkillViewTool;
import com.flocier.domain.agent.runtime.skill.SkillPromptIndexBuilder;
import com.flocier.domain.agent.runtime.desktopcontrol.DesktopControlRequiredException;
import com.flocier.domain.agent.runtime.desktopcontrol.DesktopSessionPort;
import com.flocier.domain.agent.runtime.llm.LLM;
import com.flocier.domain.agent.runtime.llm.message.LlmMessage;
import com.flocier.domain.agent.runtime.llm.message.LlmMessageRole;
import com.flocier.domain.agent.runtime.llm.message.ToolCall;
import com.flocier.domain.agent.runtime.llm.toolask.ToolAskRequest;
import com.flocier.domain.agent.runtime.llm.toolask.ToolAskResponse;
import com.flocier.domain.agent.runtime.memory.SessionContextCompactionService;
import com.flocier.domain.agent.runtime.memory.SessionWorkingMemoryRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Slf4j
@Component
public class ReActAgent extends AbstractThinkActAgent {

    public static final String REACT_TRACE_KEY = "react.trace";
    public static final String REACT_RESULT_KEY = "react.result";
    public static final String REACT_LAST_PROMPT_KEY = "react.lastPrompt";
    public static final String REACT_SYSTEM_PROMPT_KEY = "react.systemPrompt";

    @Resource
    private LLM llm;
    @Resource
    private RuntimeDependencies runtimeDependencies;
    @Resource
    private ReactPromptTemplateRenderer promptTemplateRenderer;
    @Resource
    private ToolCollectionFactory toolCollectionFactory;
    @Resource
    private ToolRunner toolRunner;
    @Resource
    private ReActToolBatchExecutor toolBatchExecutor;
    @Resource
    private SessionContextCompactionService contextCompactionService;
    @Resource
    private SessionWorkingMemoryRepository workingMemoryRepository;
    @Value("${memory.working.enabled:true}")
    private boolean workingMemoryEnabled;

    @Override
    protected void initialize(AgentContext context) {
        super.initialize(context);
        // 已提交投影直接预装；Ledger 冷启动过渡消息需要先写入当前增量段。
        if (context.getWorkingMemoryMessages() != null && !context.getWorkingMemoryMessages().isEmpty()) {
            context.getMemory().clear();
            if (Boolean.TRUE.equals(context.getAttribute("workingMemory.ledgerBootstrap"))) {
                for (LlmMessage message : context.getWorkingMemoryMessages()) appendPersisted(context, message);
            } else {
                context.getMemory().addAll(context.getWorkingMemoryMessages());
            }
        }
        context.setWorkingMemoryPreloadSize(Boolean.TRUE.equals(context.getAttribute("workingMemory.ledgerBootstrap"))
                ? 0 : context.getMemory().size());
        if (context.getMaxStep() == null) {
            context.setMaxStep(resolveReactorConfig().getReactMaxSteps());
        }
        context.setAttribute("reactModel", resolveReactorConfig().getReactModelName());
        if (context.getToolCollection() == null) {
            context.setToolCollection(toolCollectionFactory.create(context));
        }
        if (!AgentRunMode.isReview(context)) context.setAttribute(REACT_SYSTEM_PROMPT_KEY, buildSystemPrompt(context));
        String userTask = AgentRunMode.isReview(context) ? null : buildUserTaskMessage(context);
        if (StringUtils.hasText(userTask)) {
            appendPersisted(context, LlmMessage.builder().role(LlmMessageRole.USER).content(userTask).build());
        }
        context.setAttribute(REACT_TRACE_KEY, new ArrayList<String>());
        emit(context, "status", "react agent started");
    }

    @Override
    protected ReactThinkResult think(AgentContext context) {
        if (AgentRunMode.isReview(context)) context.getSkillReviewState().checkActive();
        String prompt = context.getAttribute(REACT_SYSTEM_PROMPT_KEY);
        // 每次模型决策前同步压缩；只有数据库投影替换成功才调整本轮消息和增量基准。
        if (!AgentRunMode.isReview(context)) try {
            List<LlmMessage> compacted = contextCompactionService.applyIfNeeded(
                    context.getSessionId(), context.getRequestId(), prompt,
                    requiredToolCollection(context).describeTools()
                            + JSON.toJSONString(requiredToolCollection(context).exportToolDefinitions()),
                    new ArrayList<>(context.getMemory()));
            if (!compacted.equals(context.getMemory())) {
                context.getMemory().clear();
                context.getMemory().addAll(compacted);
                context.setWorkingMemoryMessages(new ArrayList<>(compacted));
                context.setWorkingMemoryPreloadSize(compacted.size());
                context.setAttribute("workingMemory.compactionIndex",
                        ((Integer) context.getAttribute("workingMemory.compactionIndex") == null ? 0
                                : (Integer) context.getAttribute("workingMemory.compactionIndex")) + 1);
                context.setAttribute("workingMemory.segmentId", "");
                context.setAttribute("workingMemory.nextSeq", 0);
            }
        } catch (Exception e) {
            log.warn("working memory compaction failed, requestId={}", context.getRequestId(), e);
        }
        context.setAttribute(REACT_LAST_PROMPT_KEY, prompt);
        log.info("react think start, requestId={}, step={}/{}, memorySize={}",
                context.getRequestId(),
                context.getCurrentStep(),
                context.getMaxStep(),
                context.getMemory() == null ? 0 : context.getMemory().size());

        ToolAskResponse response = llm.toolAsk(ToolAskRequest.builder()
                .model(resolveReactorConfig().getReactModelName())
                .maxStep(context.getMaxStep())
                .currentStep(context.getCurrentStep())
                .toolDescriptionText(toolDescriptions(context))
                .toolDefinitions(toolDefinitions(context))
                .temperature(resolveReactorConfig().getReactTemperature())
                .messages(buildToolAskMessages(context, prompt))
                .attributes(buildLedgerAttributes(context, "react_tool_ask"))
                .build());

        if (AgentRunMode.isReview(context)) context.getSkillReviewState().checkActive();
        Object ledgerInvocationId = response == null || response.getMetadata() == null
                ? null
                : response.getMetadata().get("ledgerLlmInvocationId");
        context.setCurrentLlmInvocationId(ledgerInvocationId == null ? null : String.valueOf(ledgerInvocationId));

        ReactThinkResult thinkResult = toThinkResult(response);
        log.info("react think result, requestId={}, step={}, completed={}, toolCalls={}, finishReason={}, thought={}",
                context.getRequestId(),
                context.getCurrentStep(),
                thinkResult.isCompleted(),
                thinkResult.getToolCalls() == null ? 0 : thinkResult.getToolCalls().size(),
                thinkResult.getFinishReason(),
                shorten(thinkResult.getThought()));
        context.replaceLastToolCalls(thinkResult.getToolCalls());
        appendPersisted(context, LlmMessage.builder()
                .role(LlmMessageRole.ASSISTANT)
                .content(safe(thinkResult.getThought()))
                .toolCalls(thinkResult.getToolCalls())
                .build());

        trace(context).add("step " + context.getCurrentStep() + " thought: " + thinkResult.getThought());
        emit(context, "step", "react think step " + context.getCurrentStep());
        return thinkResult;
    }

    @Override
    protected void act(AgentContext context, ReactThinkResult thinkResult) {
        if (thinkResult == null || thinkResult.getToolCalls() == null || thinkResult.getToolCalls().isEmpty()) {
            log.info("react act skipped, requestId={}, step={}, reason=no_tool_calls",
                    context.getRequestId(),
                    context.getCurrentStep());
            context.setLastObservation("");
            return;
        }

        boolean desktopRequested = thinkResult.getToolCalls().stream().anyMatch(call -> call != null
                && call.getFunction() != null && RequestDesktopControlTool.NAME.equals(call.getFunction().getName()));
        if (desktopRequested && thinkResult.getToolCalls().size() != 1) {
            for (ToolCall call : thinkResult.getToolCalls()) {
                if (call != null && call.getId() != null) {
                    appendPersisted(context, LlmMessage.builder().role(LlmMessageRole.TOOL).toolCallId(call.getId())
                            .content("RequestDesktopControl 必须是本轮唯一 tool call；本轮工具均未执行，请单独重试。")
                            .build());
                }
            }
            context.setLastObservation("RequestDesktopControl 必须是本轮唯一 tool call");
            return;
        }

        StringBuilder observation = new StringBuilder();
        ToolCollection toolCollection = requiredToolCollection(context);
        log.info("react act start, requestId={}, step={}, toolCalls={}",
                context.getRequestId(),
                context.getCurrentStep(),
                thinkResult.getToolCalls().size());

        List<PreparedToolExecution> preparedExecutions = new ArrayList<>();
        int dispatchOrder = 0;
        for (ToolCall toolCall : thinkResult.getToolCalls()) {
            if (toolCall == null || toolCall.getFunction() == null) {
                continue;
            }
            ToolInvokeRequest invokeRequest = toInvokeRequest(toolCall, context, dispatchOrder++);
            log.info("react act invoke, requestId={}, step={}, toolName={}, toolCallId={}, arguments={}",
                    context.getRequestId(),
                    context.getCurrentStep(),
                    toolCall.getFunction().getName(),
                    toolCall.getId(),
                    toolCall.getFunction().getArguments());
            preparedExecutions.add(toolRunner.prepare(toolCollection, invokeRequest, context));
        }

        // code_execution 与本轮其他工作区工具共享文件，必须按模型给出的顺序执行。
        boolean serialWorkspaceExecution = preparedExecutions.stream().anyMatch(prepared ->
                "code_execution".equals(prepared.getRequest().getToolName()));
        List<CompletableFuture<ToolExecutionResult>> executionFutures = preparedExecutions.stream()
                .map(prepared -> prepared.isReady()
                        ? (serialWorkspaceExecution
                            ? CompletableFuture.completedFuture(executePrepared(prepared, context))
                            : CompletableFuture.supplyAsync(() -> executePrepared(prepared, context),
                                    AgentRunMode.isReview(context) ? Runnable::run : toolExecutor()))
                        : CompletableFuture.completedFuture(prepared.getPreparationFailure()))
                .toList();
        CompletableFuture.allOf(executionFutures.toArray(new CompletableFuture[0])).join();

        for (int index = 0; index < preparedExecutions.size(); index++) {
            PreparedToolExecution prepared = preparedExecutions.get(index);
            ToolExecutionResult executionResult = executionFutures.get(index).join();
            appendPersisted(context, LlmMessage.builder()
                    .role(LlmMessageRole.TOOL)
                    .toolCallId(prepared.getRequest().getToolCallId())
                    .content(buildToolResponseContent(executionResult))
                    .build());
            context.appendToolExecutionResult(executionResult);
            if (AgentRunMode.isReview(context)) context.getSkillReviewState().toolResult(executionResult.getToolName(), executionResult.isSuccess());

            if (!observation.isEmpty()) {
                observation.append("\n");
            }
            observation.append("[").append(executionResult.getToolCallId()).append("] ")
                    .append(executionResult.getToolName()).append(": ")
                    .append(resolveToolObservation(executionResult));
            trace(context).add("step " + context.getCurrentStep() + " act: " + executionResult.getToolName());
            log.info("react act result, requestId={}, step={}, toolName={}, success={}, output={}",
                    context.getRequestId(),
                    context.getCurrentStep(),
                    executionResult.getToolName(),
                    executionResult.isSuccess(),
                    shorten(resolveToolObservation(executionResult)));
            emit(context, "tool", executionResult);
            if (RequestDesktopControlTool.NAME.equals(executionResult.getToolName())
                    && executionResult.isSuccess() && "waiting_user_input".equals(executionResult.getStatus())) {
                DesktopSessionPort.OpenedDesktop opened = context.getAttribute(RequestDesktopControlTool.OPENED_ATTRIBUTE);
                if (opened == null) throw new IllegalStateException("桌面打开结果丢失");
                Object reason = prepared.getRequest().getArguments().get("reason");
                throw new DesktopControlRequiredException(String.valueOf(reason), opened.url(),
                        opened.holdUntil(), executionResult.getToolCallId());
            }
        }

        context.setLastObservation(observation.toString());
        log.info("react act completed, requestId={}, step={}, observation={}",
                context.getRequestId(),
                context.getCurrentStep(),
                shorten(context.getLastObservation()));
        emit(context, "status", "react act step " + context.getCurrentStep() + " completed");
    }

    private ToolExecutionResult executePrepared(PreparedToolExecution prepared, AgentContext context) {
        try {
            return toolRunner.execute(prepared, context);
        } catch (RuntimeException exception) {
            log.error("react prepared tool execution failed unexpectedly, toolName={}, toolCallId={}",
                    prepared.getRequest().getToolName(), prepared.getRequest().getToolCallId(), exception);
            return ToolExecutionResult.builder()
                    .toolName(prepared.getRequest().getToolName())
                    .toolCallId(prepared.getRequest().getToolCallId())
                    .sourceType(prepared.getRequest().getSourceHint())
                    .success(false)
                    .status("failed")
                    .errorCode("tool_execute_error")
                    .errorMessage(exception.getMessage())
                    .arguments(new LinkedHashMap<>(prepared.getRequest().getArguments()))
                    .metadata(new LinkedHashMap<>(prepared.getRequest().getMetadata()))
                    .outputText("工具执行异常。")
                    .llmObservation("工具 " + prepared.getRequest().getToolName() + " 执行异常，请基于已完成的其他工具结果继续决策。")
                    .structuredOutput(Map.of("toolName", prepared.getRequest().getToolName(), "errorCode", "tool_execute_error"))
                    .ledgerFacts(List.of())
                    .artifactRefs(List.of())
                    .attributes(new LinkedHashMap<>())
                    .startedAt(prepared.getStartedAt())
                    .finishedAt(java.time.Instant.now())
                    .durationMs(java.time.Duration.between(prepared.getStartedAt(), java.time.Instant.now()).toMillis())
                    .build();
        }
    }

    private java.util.concurrent.Executor toolExecutor() {
        return toolBatchExecutor == null ? Runnable::run : toolBatchExecutor;
    }

    @Override
    protected String finalizeRun(AgentContext context, ReactThinkResult thinkResult) {
        if (thinkResult != null && thinkResult.isCompleted()) {
            context.setCompleted(Boolean.TRUE);
            if (!StringUtils.hasText(context.getFinishReason())) {
                context.setFinishReason(StringUtils.hasText(thinkResult.getFinishReason())
                        ? thinkResult.getFinishReason()
                        : "llm_completed");
            }
        }
        String result = resolveResult(context, thinkResult);
        context.setResult(result);
        context.setAttribute(REACT_RESULT_KEY, result);
        log.info("react finalize, requestId={}, finishReason={}, result={}",
                context.getRequestId(),
                context.getFinishReason(),
                shorten(result));
        return result;
    }

    private ReactorConfig resolveReactorConfig() {
        return runtimeDependencies.reactorConfig();
    }

    /** 构造本次运行保持稳定的 SYSTEM 指令和工具目录，不重复拼接历史消息。 */
    private String buildSystemPrompt(AgentContext context) {
        String toolList = requiredToolCollection(context).listTools().stream()
                .sorted(Comparator.comparing(tool -> tool.name(), String.CASE_INSENSITIVE_ORDER))
                .map(tool -> "- name: " + tool.name()
                        + "\n  description: " + tool.description()
                        + "\n  params: " + tool.params())
                .collect(java.util.stream.Collectors.joining("\n"));
        String rendered = promptTemplateRenderer.render(resolveReactorConfig().resolveReactSystemPrompt(),
                Map.of("tool_list", toolList));
        String prompt = com.flocier.domain.agent.runtime.agent.react.prompt.IntentGatedPrompt
                .select(context.getQuery(), requiredToolCollection(context)).appendTo(rendered);
        return requiredToolCollection(context).getOptional("skill_view")
                .filter(SkillViewTool.class::isInstance)
                .map(SkillViewTool.class::cast)
                .map(view -> prompt.contains(SkillPromptIndexBuilder.OPEN)
                        ? prompt : prompt.stripTrailing() + "\n\n" + view.promptIndex())
                .orElse(prompt);
    }

    /** 当前 USER 只追加一次；query 与 task 不同时一起保留以免丢失原话。 */
    private String buildUserTaskMessage(AgentContext context) {
        String query = context.getQuery();
        String task = context.getTask();
        if (!StringUtils.hasText(query)) {
            return task;
        }
        if (!StringUtils.hasText(task) || query.trim().equals(task.trim())) {
            return query;
        }
        return "用户原话：\n" + query + "\n\n当前任务：\n" + task;
    }

    @SuppressWarnings("unchecked")
    private List<String> trace(AgentContext context) {
        return context.getAttribute(REACT_TRACE_KEY);
    }

    private String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private ToolCollection requiredToolCollection(AgentContext context) {
        if (context.getToolCollection() == null) {
            throw new IllegalStateException("ToolCollection has not been initialized");
        }
        return context.getToolCollection();
    }

    private ToolInvokeRequest toInvokeRequest(ToolCall toolCall, AgentContext context, int dispatchOrder) {
        return ToolInvokeRequest.builder()
                .toolName(toolCall.getFunction().getName())
                .toolCallId(toolCall.getId())
                .arguments(parseArguments(toolCall.getFunction().getArguments()))
                .metadata(buildToolInvokeMetadata(context, dispatchOrder))
                .requestId(context.getRequestId())
                .sessionId(context.getSessionId())
                .step(context.getCurrentStep())
                .build();
    }

    private Map<String, Object> buildToolInvokeMetadata(AgentContext context, int dispatchOrder) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (StringUtils.hasText(context.getCurrentLlmInvocationId())) {
            metadata.put("llmInvocationId", context.getCurrentLlmInvocationId());
        }
        metadata.put("dispatchOrder", dispatchOrder);
        return metadata;
    }

    private Map<String, Object> parseArguments(String arguments) {
        if (!StringUtils.hasText(arguments)) {
            return Map.of();
        }
        try {
            return JSON.parseObject(arguments, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("parse tool arguments failed, fallback to empty map. arguments={}", arguments, e);
            return Map.of();
        }
    }

    private ReactThinkResult toThinkResult(ToolAskResponse response) {
        if (response == null) {
            return ReactThinkResult.builder()
                    .completed(true)
                    .thought("")
                    .finishReason("empty_toolAsk_response")
                    .rawResponse("")
                    .toolCalls(List.of())
                    .build();
        }
        boolean completed = response.isCompleted() || response.getToolCalls() == null || response.getToolCalls().isEmpty();
        return ReactThinkResult.builder()
                .completed(completed)
                .thought(response.getThought())
                .finishReason(response.getFinishReason())
                .rawResponse(response.getRawResponse())
                .toolCalls(response.getToolCalls() == null ? List.of() : response.getToolCalls())
                .build();
    }

    /** 模型实际输入为稳定 SYSTEM 加有序原生消息，供工具调用后继续对话。 */
    private List<LlmMessage> buildToolAskMessages(AgentContext context, String prompt) {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.builder()
                .role(LlmMessageRole.SYSTEM)
                .content(prompt)
                .build());
        messages.addAll(context.getMemory());
        return messages;
    }

    private String buildToolResponseContent(ToolExecutionResult executionResult) {
        return resolveToolObservation(executionResult);
    }

    /** 并发工具只在主线程的有序汇总处追加，数据库序号与模型输入顺序一致。 */
    private void appendPersisted(AgentContext context, LlmMessage message) {
        if (workingMemoryEnabled && !AgentRunMode.isReview(context)) {
            String segmentId = context.getAttribute("workingMemory.segmentId");
            if (!StringUtils.hasText(segmentId)) {
                Integer compactIndex = context.getAttribute("workingMemory.compactionIndex");
                segmentId = compactIndex == null || compactIndex == 0 ? context.getRequestId()
                        : "wm-segment-" + UUID.nameUUIDFromBytes(
                                (context.getRequestId() + ":" + compactIndex).getBytes(StandardCharsets.UTF_8));
            }
            Integer nextSeq = context.getAttribute("workingMemory.nextSeq");
            int seqNo = nextSeq == null ? 0 : nextSeq;
            workingMemoryRepository.appendMessage(context.getSessionId(), segmentId,
                    (String) context.getAttribute("runId"), seqNo, message);
            context.setAttribute("workingMemory.segmentId", segmentId);
            context.setAttribute("workingMemory.nextSeq", seqNo + 1);
        }
        context.appendMemory(message);
    }

    /**
     * TOOL message 与 lastObservation 优先消费工具自带 llmObservation，
     * 只有旧协议或兼容路径下才回退到通用文本结果。
     */
    private String resolveToolObservation(ToolExecutionResult executionResult) {
        if (executionResult == null) {
            return "";
        }
        if (StringUtils.hasText(executionResult.getLlmObservation())) {
            return executionResult.getLlmObservation();
        }
        return safe(executionResult.getPreferredObservationText());
    }

    private Map<String, Object> buildLedgerAttributes(AgentContext context, String scene) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("mode", "toolAsk");
        attributes.put(AgentRunMode.ATTRIBUTE, context.getRunMode().name());
        attributes.put("scene", scene);
        putIfPresent(attributes, "agentId", context.getAttribute("agentId"));
        putIfPresent(attributes, "runId", context.getAttribute("runId"));
        attributes.put("requestId", context.getRequestId());
        attributes.put("sessionId", context.getSessionId());
        attributes.put("traceId", StringUtils.hasText(context.getTraceId()) ? context.getTraceId() : context.getRequestId());
        attributes.put("step", context.getCurrentStep());
        return attributes;
    }

    /** 保留父请求定义前缀，执行权限由运行模式及独立集合控制。 */
    private String toolDescriptions(AgentContext context) {
        String frozen = context.getAttribute("review.toolDescriptions");
        return AgentRunMode.isReview(context) && frozen != null ? frozen : requiredToolCollection(context).describeTools();
    }

    private Map<String, Object> toolDefinitions(AgentContext context) {
        Map<String, Object> frozen = context.getAttribute("review.toolDefinitions");
        return AgentRunMode.isReview(context) && frozen != null ? frozen : requiredToolCollection(context).exportToolDefinitions();
    }

    private String shorten(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (normalized.length() <= 200) {
            return normalized;
        }
        return normalized.substring(0, 200) + "...";
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

}
