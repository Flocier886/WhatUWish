package com.flocier.domain.agent.runtime.llm.toolask;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolAskValidationResult {

    private boolean valid;

    private String reason;
}
