package com.flocier.domain.agent.runtime.agent.tool.docgen;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 保留参照项目各文档工具的完整模型可见契约，执行由本站 MaterialTool 负责。 */
public abstract class AbstractDocGenTool {
    public abstract String getName();
    protected abstract String endpointPath();
    protected abstract String defaultDescription();
    protected abstract Map<String, Object> defaultParams();

    public final String description() { return defaultDescription(); }
    public final Map<String, Object> params() { return defaultParams(); }

    protected static Map<String, Object> stringProp(String description) {
        return Map.of("type", "string", "description", description);
    }

    protected static Map<String, Object> boolProp(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    protected static Map<String, Object> arrayProp(String description, Map<String, Object> items) {
        return Map.of("type", "array", "description", description, "items", items);
    }

    protected static Map<String, Object> objectProp(String description) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", "object");
        value.put("description", description);
        value.put("properties", new LinkedHashMap<String, Object>());
        value.put("required", List.of());
        return value;
    }

    protected static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", "object");
        value.put("properties", properties);
        value.put("required", required);
        return value;
    }

    protected static String formatList(String... items) { return String.join(", ", items); }
}
