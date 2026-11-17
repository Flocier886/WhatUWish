package com.flocier.test.domain.llm;

import com.flocier.domain.agent.runtime.agent.react.prompt.ReactPromptTemplateRenderer;
import com.flocier.domain.agent.runtime.agent.react.prompt.IntentGatedPrompt;
import com.flocier.domain.agent.reactor.config.ReactorConfig;
import com.flocier.domain.agent.runtime.agent.tool.ToolCollection;
import com.flocier.domain.agent.runtime.agent.tool.canvas.GetHtmlCanvasGuideTool;
import com.flocier.domain.agent.runtime.agent.tool.BaseTool;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import org.junit.Test;

import java.util.Map;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class ReactPromptTemplateRendererTest {

    @Test
    public void shouldUseAlignedDefaultReactPromptWithoutLegacyToolList() {
        ReactorConfig config = new ReactorConfig();
        String prompt = config.resolveReactSystemPrompt();

        org.junit.Assert.assertTrue(prompt.contains("Think, then act"));
        org.junit.Assert.assertTrue(prompt.contains("Surface failures clearly"));
        org.junit.Assert.assertTrue(prompt.contains("skill_view(name)"));
        org.junit.Assert.assertTrue(prompt.contains("skills_search"));
        org.junit.Assert.assertFalse(prompt.contains("{{tool_list}}"));
    }

    @Test
    public void shouldRenderSimpleTemplateVariables() {
        ReactPromptTemplateRenderer renderer = new ReactPromptTemplateRenderer();

        String rendered = renderer.render(
                "task={{task}}, query={{query}}, tools={{tool_list}}",
                Map.of(
                        "task", "帮我处理一个任务",
                        "query", "你好",
                        "tool_list", "echo_tool"
                )
        );

        assertEquals("task=帮我处理一个任务, query=你好, tools=echo_tool", rendered);
    }

    @Test
    public void shouldSelectMasterDocumentOrCanvasPolicyOnlyForMatchingIntent() {
        BaseTool document = new BaseTool() {
            public String name() { return "document_generate"; }
            public String description() { return "document"; }
            public Object params() { return Map.of(); }
            public Object execute(Object input, AgentContext context) { return null; }
        };
        var tools = new ToolCollection(List.of(document, new GetHtmlCanvasGuideTool()));
        var canvasTools = new ToolCollection(List.of(new com.flocier.domain.agent.runtime.agent.tool.canvas.CanvasPublishTool(null, null)));
        org.junit.Assert.assertTrue(IntentGatedPrompt.select("生成 PDF 报告", tools)
                .appendTo("base").contains("文档生成策略"));
        org.junit.Assert.assertTrue(IntentGatedPrompt.select("制作一个网页", canvasTools)
                .appendTo("base").contains("Canvas 与 GenUI 策略"));
        assertEquals("base", IntentGatedPrompt.select("解释概念", tools).appendTo("base"));
    }
}
