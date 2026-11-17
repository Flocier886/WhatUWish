package com.flocier.domain.agent.runtime.llm.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCall {

    private String id;

    private String type;

    private Function function;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Function {

        private String name;

        private String arguments;
    }
}
