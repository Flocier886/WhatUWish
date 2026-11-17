package com.flocier.domain.agent.runtime.llm.toolask;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.flocier.domain.agent.runtime.llm.message.ToolCall;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ToolAskValidator {

    public ToolAskValidationResult validate(ToolAskRequest request, ToolAskResponse response) {
        if (response == null) {
            return invalid("toolAsk response is null");
        }
        if (!response.isCompleted() && (response.getToolCalls() == null || response.getToolCalls().isEmpty())) {
            return invalid("toolAsk must provide toolCalls when completed=false");
        }
        if (response.getToolCalls() == null || response.getToolCalls().isEmpty()) {
            return ToolAskValidationResult.builder().valid(true).reason("ok").build();
        }

        Set<String> allowedTools = request.getToolDefinitions().keySet();
        for (ToolCall toolCall : response.getToolCalls()) {
            if (toolCall == null || toolCall.getFunction() == null) {
                return invalid("tool call item is null");
            }
            String toolName = toolCall.getFunction().getName();
            if (!allowedTools.contains(toolName)) {
                return invalid("tool name is not allowed: " + toolName);
            }
            ToolAskValidationResult argumentResult = validateArguments(toolCall, request.getToolDefinitions().get(toolName));
            if (!argumentResult.isValid()) {
                return argumentResult;
            }
        }

        return ToolAskValidationResult.builder().valid(true).reason("ok").build();
    }

    @SuppressWarnings("unchecked")
    private ToolAskValidationResult validateArguments(ToolCall toolCall, Object toolDefinition) {
        if (!(toolDefinition instanceof Map<?, ?> definition)) {
            return ToolAskValidationResult.builder().valid(true).reason("ok").build();
        }
        Object paramsObject = definition.get("params");
        if (!(paramsObject instanceof Map<?, ?> params)) {
            return ToolAskValidationResult.builder().valid(true).reason("ok").build();
        }

        Map<String, Object> arguments = parseArguments(toolCall.getFunction().getArguments());
        Object propertiesObject = params.get("properties");
        if (propertiesObject instanceof Map<?, ?> properties) {
            for (String argumentName : arguments.keySet()) {
                if (!properties.containsKey(argumentName)) {
                    return invalid("tool argument is not allowed: " + toolCall.getFunction().getName() + "." + argumentName);
                }
            }
        }

        Object requiredObject = params.get("required");
        if (requiredObject instanceof Collection<?> requiredCollection) {
            for (Object required : requiredCollection) {
                String requiredName = String.valueOf(required);
                if (!arguments.containsKey(requiredName)) {
                    return invalid("tool required argument is missing: " + toolCall.getFunction().getName() + "." + requiredName);
                }
            }
        } else if (requiredObject instanceof Object[] requiredArray) {
            for (Object required : requiredArray) {
                String requiredName = String.valueOf(required);
                if (!arguments.containsKey(requiredName)) {
                    return invalid("tool required argument is missing: " + toolCall.getFunction().getName() + "." + requiredName);
                }
            }
        } else if (requiredObject instanceof List<?> requiredList) {
            for (Object required : requiredList) {
                String requiredName = String.valueOf(required);
                if (!arguments.containsKey(requiredName)) {
                    return invalid("tool required argument is missing: " + toolCall.getFunction().getName() + "." + requiredName);
                }
            }
        }
        return ToolAskValidationResult.builder().valid(true).reason("ok").build();
    }

    private Map<String, Object> parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.parseObject(arguments, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private ToolAskValidationResult invalid(String reason) {
        return ToolAskValidationResult.builder().valid(false).reason(reason).build();
    }
}
