package com.flocier.test.domain.llm;

import com.flocier.domain.agent.runtime.agent.tool.ToolCollection;
import com.flocier.domain.agent.runtime.agent.tool.impl.EchoTool;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertTrue;

public class ToolCollectionTest {

    @Test
    public void shouldDescribeRegisteredTools() {
        ToolCollection toolCollection = new ToolCollection(List.of(
                new EchoTool("echo_tool", "回显输入参数", Map.of("type", "object"))
        ));

        String description = toolCollection.describeTools();

        assertTrue(description.contains("echo_tool"));
        assertTrue(description.contains("回显输入参数"));
    }
}
