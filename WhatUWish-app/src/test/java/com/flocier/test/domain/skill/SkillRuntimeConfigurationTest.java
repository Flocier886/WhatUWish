package com.flocier.test.domain.skill;

import com.flocier.domain.agent.reactor.config.ReactorConfig;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

public class SkillRuntimeConfigurationTest {

    private static final List<String> MASTER_STYLE_RUNTIMES =
            List.of("python", "node", "shell", "powershell", "bat");

    @Test
    public void shouldEnableAllMasterStyleRuntimesInInitialDefaults() {
        ReactorConfig config = new ReactorConfig();

        Assert.assertEquals(MASTER_STYLE_RUNTIMES, config.getSkillRuntimeOptions().getAllowedScriptRuntimes());
    }

    @Test
    public void shouldEnableAllMasterStyleRuntimesForEmptyConfiguration() {
        ReactorConfig config = new ReactorConfig();

        config.setSkillRuntimeOptions("{}");

        Assert.assertEquals(MASTER_STYLE_RUNTIMES, config.getSkillRuntimeOptions().getAllowedScriptRuntimes());
    }

    @Test
    public void shouldEnableAllMasterStyleRuntimesWhenAllowListIsOmitted() {
        ReactorConfig config = new ReactorConfig();

        config.setSkillRuntimeOptions("{\"enabled\":true,\"directories\":[\"runtime/skills\"]}");

        Assert.assertEquals(MASTER_STYLE_RUNTIMES, config.getSkillRuntimeOptions().getAllowedScriptRuntimes());
    }
}
