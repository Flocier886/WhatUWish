package com.flocier.test.domain.react;

import com.flocier.domain.agent.runtime.agent.react.prompt.ReactPromptTemplateRenderer;
import com.flocier.domain.agent.reactor.config.ReactorConfig;
import org.junit.Assert;
import org.junit.Test;

import java.util.Map;

public class MasterStyleSkillRuntimeFlowTest {

    @Test
    public void shouldPreferMasterStyleSkillToolsInRenderedReactPrompt() {
        ReactPromptTemplateRenderer renderer = new ReactPromptTemplateRenderer();

        String prompt = renderer.render(new ReactorConfig().resolveReactSystemPrompt(), Map.of());
        Assert.assertTrue(prompt.contains("skill_view(name)"));
        Assert.assertTrue(prompt.contains("skills_search"));
        Assert.assertTrue(prompt.contains("workspace_edit"));
        Assert.assertTrue(prompt.contains("bash"));
        Assert.assertFalse(prompt.contains("skill_script_runner_tool"));
    }
}
