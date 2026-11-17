package com.flocier.domain.agent.runtime.agent.tool;

import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.LinkedHashMap;

@RequiredArgsConstructor
public class ToolCollection {

    private final List<BaseTool> tools;

    public BaseTool getRequired(String toolName) {
        return getOptional(toolName)
                .orElseThrow(() -> new IllegalStateException("Tool not found: " + toolName));
    }

    public Optional<BaseTool> getOptional(String toolName) {
        return Optional.ofNullable(toolMap().get(toolName));
    }

    public List<BaseTool> listTools() {
        return tools;
    }

    public String describeTools() {
        return tools.stream()
                .map(tool -> "- name: " + tool.name()
                        + "\n  description: " + tool.description()
                        + "\n  params: " + String.valueOf(tool.params()))
                .collect(Collectors.joining("\n"));
    }

    public Map<String, Object> exportToolDefinitions() {
        Map<String, Object> definitions = new LinkedHashMap<>();
        for (BaseTool tool : tools) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", tool.name());
            item.put("description", tool.description());
            item.put("params", tool.params());
            definitions.put(tool.name(), item);
        }
        return definitions;
    }

    public ToolCollection subset(List<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return new ToolCollection(List.of());
        }
        List<BaseTool> subset = tools.stream()
                .filter(tool -> toolNames.contains(tool.name()))
                .collect(Collectors.toList());
        return new ToolCollection(subset);
    }

    private Map<String, BaseTool> toolMap() {
        Map<String, BaseTool> map = new ConcurrentHashMap<>();
        for (BaseTool tool : tools) {
            map.put(tool.name(), tool);
        }
        return map;
    }
}
