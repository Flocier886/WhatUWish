package com.flocier.domain.agent.runtime.agent.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolInvokeRequest {

    private String toolName;

    private String toolCallId;

    @Builder.Default
    private Map<String, Object> arguments = new LinkedHashMap<>();

    @Builder.Default
    private Map<String, Object> metadata = new LinkedHashMap<>();

    private String sourceHint;

    private String requestId;

    private String sessionId;

    private Integer step;
}
