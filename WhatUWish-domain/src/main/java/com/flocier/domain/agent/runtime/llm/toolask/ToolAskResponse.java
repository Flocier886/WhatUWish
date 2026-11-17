package com.flocier.domain.agent.runtime.llm.toolask;

import com.flocier.domain.agent.runtime.llm.message.ToolCall;
import com.flocier.domain.agent.runtime.llm.response.LlmUsage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolAskResponse {

    private boolean completed;

    private String finishReason;

    private String thought;

    @Builder.Default
    private List<ToolCall> toolCalls = new ArrayList<>();

    private String rawResponse;

    private String model;

    private String provider;

    private LlmUsage usage;

    @Builder.Default
    private Map<String, Object> metadata = new LinkedHashMap<>();
}
