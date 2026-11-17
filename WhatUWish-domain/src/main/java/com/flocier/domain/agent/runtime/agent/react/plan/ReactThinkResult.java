package com.flocier.domain.agent.runtime.agent.react.plan;

import com.flocier.domain.agent.runtime.llm.message.ToolCall;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReactThinkResult {

    private boolean completed;

    private String thought;

    @Builder.Default
    private List<ToolCall> toolCalls = new ArrayList<>();

    private String rawResponse;

    private String finishReason;
}
