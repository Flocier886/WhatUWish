package com.flocier.domain.agent.runtime.agent.tool.rich.deepsearch;

import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import lombok.Getter;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Java 向独立 Python DeepSearch Rich API 发送的受控请求。 */
@Getter
public class DeepSearchStartRequest {

    private static final List<String> ALLOWED_ARGUMENTS = List.of(
            "topic", "questions", "language", "preferredDomains", "researchDepth"
    );

    private final Map<String, Object> payload;

    private DeepSearchStartRequest(Map<String, Object> payload) {
        this.payload = payload;
    }

    public static DeepSearchStartRequest from(ToolInvokeRequest request, String toolInvocationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, Object> arguments = request == null || request.getArguments() == null
                ? Map.of()
                : request.getArguments();
        for (String name : ALLOWED_ARGUMENTS) {
            Object value = arguments.get(name);
            if (value != null) {
                payload.put(name, value);
            }
        }
        if (!StringUtils.hasText(stringValue(payload.get("topic")))) {
            throw new IllegalArgumentException("deep_search_tool requires a non-blank topic");
        }
        // Python 请求模型默认 standard。显式补齐后，Java 的同 Run 范围指纹与 Python 终态指纹
        // 才能对同一用户请求计算出相同值。
        payload.putIfAbsent("researchDepth", "standard");
        // Java 账本身份始终是主关联；此值只供 Python SSE 在本次请求内回显和关联。
        payload.put("executionId", "java-tool-invocation:" + toolInvocationId);
        return new DeepSearchStartRequest(payload);
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }
}
