package com.flocier.domain.agent.runtime.llm.message;

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
public class LlmMessage {

    private LlmMessageRole role;

    private String content;

    @Builder.Default
    private List<ToolCall> toolCalls = new ArrayList<>();

    private String toolCallId;

    @Builder.Default
    private Map<String, Object> metadata = new LinkedHashMap<>();
}
