package com.flocier.domain.agent.runtime.agent.tool.impl;

import com.alibaba.fastjson.JSON;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.BaseTool;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class EchoTool implements BaseTool {

    private final String name;
    private final String description;
    private final Object params;

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public Object params() {
        return params;
    }

    @Override
    public Object execute(Object input, AgentContext context) {
        String result = JSON.toJSONString(input);
        return ToolResultPayload.success(result, result, input);
    }
}
