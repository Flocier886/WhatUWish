package com.flocier.domain.agent.runtime.llm.options;

import com.flocier.domain.agent.runtime.llm.config.LlmModelConfig;
import com.flocier.domain.agent.runtime.llm.message.LlmRequest;
import com.flocier.domain.agent.runtime.llm.support.LlmResolvedModel;
import com.flocier.domain.agent.runtime.llm.toolask.ToolAskRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class OpenAiChatOptionsFactory {

    public OpenAiChatOptions build(LlmResolvedModel resolvedModel, LlmRequest request) {
        LlmModelConfig config = resolvedModel.getConfig();
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .model(config.getModel())
                .temperature(request.getTemperature() != null ? request.getTemperature() : config.getTemperature())
                .maxTokens(request.getMaxTokens() != null ? request.getMaxTokens() : config.getMaxTokens());

        applyExtParams(builder, config.getExtParams());
        return builder.build();
    }

    public OpenAiChatOptions buildToolCallOptions(LlmResolvedModel resolvedModel, ToolAskRequest request) {
        LlmModelConfig config = resolvedModel.getConfig();
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .model(config.getModel())
                .temperature(request.getTemperature() != null ? request.getTemperature() : config.getTemperature())
                .maxTokens(request.getMaxTokens() != null ? request.getMaxTokens() : config.getMaxTokens())
                .tools(toFunctionTools(request.getToolDefinitions()))
                .internalToolExecutionEnabled(Boolean.FALSE)
                .parallelToolCalls(Boolean.FALSE);

        applyExtParams(builder, config.getExtParams());
        return builder.build();
    }

    private List<OpenAiApi.FunctionTool> toFunctionTools(Map<String, Object> definitions) {
        if (definitions == null || definitions.isEmpty()) {
            return List.of();
        }

        List<OpenAiApi.FunctionTool> tools = new ArrayList<>();
        for (Map.Entry<String, Object> entry : definitions.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> item)) {
                continue;
            }

            String name = item.get("name") == null ? entry.getKey() : String.valueOf(item.get("name"));
            String description = item.get("description") == null ? "" : String.valueOf(item.get("description"));
            Map<String, Object> params = asMap(item.get("params"));

            OpenAiApi.FunctionTool.Function function = new OpenAiApi.FunctionTool.Function(
                    description,
                    name,
                    params,
                    Boolean.FALSE
            );
            tools.add(new OpenAiApi.FunctionTool(function));
        }
        return tools;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> mapValue) {
            Map<String, Object> copied = new LinkedHashMap<>();
            mapValue.forEach((key, item) -> copied.put(String.valueOf(key), item));
            return copied;
        }
        return Map.of();
    }

    private void applyExtParams(OpenAiChatOptions.Builder builder, Map<String, Object> extParams) {
        if (extParams == null || extParams.isEmpty()) {
            return;
        }

        Map<String, Object> extraBody = new LinkedHashMap<>(extParams);

        Double topP = removeDouble(extraBody, "top_p", "topP");
        if (topP != null) {
            builder.topP(topP);
        }

        Double frequencyPenalty = removeDouble(extraBody, "frequency_penalty", "frequencyPenalty");
        if (frequencyPenalty != null) {
            builder.frequencyPenalty(frequencyPenalty);
        }

        Double presencePenalty = removeDouble(extraBody, "presence_penalty", "presencePenalty");
        if (presencePenalty != null) {
            builder.presencePenalty(presencePenalty);
        }

        Integer maxCompletionTokens = removeInteger(extraBody, "max_completion_tokens", "maxCompletionTokens");
        if (maxCompletionTokens != null) {
            builder.maxCompletionTokens(maxCompletionTokens);
        }

        Integer seed = removeInteger(extraBody, "seed");
        if (seed != null) {
            builder.seed(seed);
        }

        String reasoningEffort = removeString(extraBody, "reasoning_effort", "reasoningEffort");
        if (StringUtils.isNotBlank(reasoningEffort)) {
            builder.reasoningEffort(reasoningEffort);
        }

        String verbosity = removeString(extraBody, "verbosity");
        if (StringUtils.isNotBlank(verbosity)) {
            builder.verbosity(verbosity);
        }

        String user = removeString(extraBody, "user");
        if (StringUtils.isNotBlank(user)) {
            builder.user(user);
        }

        Boolean store = removeBoolean(extraBody, "store");
        if (store != null) {
            builder.store(store);
        }

        List<String> stop = removeStringList(extraBody, "stop", "stop_sequences", "stopSequences");
        if (!stop.isEmpty()) {
            builder.stop(stop);
        }

        if (!extraBody.isEmpty()) {
            builder.extraBody(extraBody);
        }
    }

    private Double removeDouble(Map<String, Object> source, String... keys) {
        Object value = removeFirst(source, keys);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String stringValue && StringUtils.isNotBlank(stringValue)) {
            return Double.parseDouble(stringValue.trim());
        }
        return null;
    }

    private Integer removeInteger(Map<String, Object> source, String... keys) {
        Object value = removeFirst(source, keys);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String stringValue && StringUtils.isNotBlank(stringValue)) {
            return Integer.parseInt(stringValue.trim());
        }
        return null;
    }

    private Boolean removeBoolean(Map<String, Object> source, String... keys) {
        Object value = removeFirst(source, keys);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String stringValue && StringUtils.isNotBlank(stringValue)) {
            return Boolean.parseBoolean(stringValue.trim());
        }
        return null;
    }

    private String removeString(Map<String, Object> source, String... keys) {
        Object value = removeFirst(source, keys);
        return value == null ? null : String.valueOf(value);
    }

    private List<String> removeStringList(Map<String, Object> source, String... keys) {
        Object value = removeFirst(source, keys);
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> listValue) {
            List<String> result = new ArrayList<>();
            for (Object item : listValue) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }
        if (value instanceof String stringValue && StringUtils.isNotBlank(stringValue)) {
            return List.of(stringValue.trim());
        }
        return List.of();
    }

    private Object removeFirst(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            if (source.containsKey(key)) {
                return source.remove(key);
            }
        }
        return null;
    }
}
