package com.flocier.domain.agent.runtime.agent.react.prompt;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * ReAct Prompt 模板渲染器，仅支持简单占位符替换。
 */
@Component
public class ReactPromptTemplateRenderer {

    public String render(String template, Map<String, String> variables) {
        String rendered = template == null ? "" : template;
        if (variables == null || variables.isEmpty()) {
            return rendered;
        }
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String value = entry.getValue() == null ? "" : entry.getValue();
            rendered = rendered.replace("{{" + entry.getKey() + "}}", value);
        }
        return rendered;
    }
}
