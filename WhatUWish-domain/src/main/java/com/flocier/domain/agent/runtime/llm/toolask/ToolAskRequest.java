package com.flocier.domain.agent.runtime.llm.toolask;

import com.flocier.domain.agent.runtime.llm.message.LlmMessage;
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
public class ToolAskRequest {

    private String model;

    @Builder.Default
    private List<LlmMessage> messages = new ArrayList<>();

    private Double temperature;

    private Integer maxTokens;

    private String toolDescriptionText;

    @Builder.Default
    private Map<String, Object> toolDefinitions = new LinkedHashMap<>();

    private Integer currentStep;

    private Integer maxStep;

    @Builder.Default
    private Map<String, Object> attributes = new LinkedHashMap<>();
}
