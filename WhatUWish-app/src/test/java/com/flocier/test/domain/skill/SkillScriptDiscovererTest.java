package com.flocier.test.domain.skill;

import com.flocier.domain.agent.runtime.skill.SkillPathGuard;
import com.flocier.domain.agent.runtime.skill.SkillRuntimeOptions;
import com.flocier.domain.agent.runtime.skill.SkillScriptDefinition;
import com.flocier.domain.agent.runtime.skill.SkillScriptDiscoverer;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class SkillScriptDiscovererTest {

    @Test
    public void shouldPreferScriptsYamlMapDeclarationOverAutoScan() throws Exception {
        Path skill = createSkillPackage("map-demo");
        Files.writeString(skill.resolve("scripts.yaml"), """
                scripts:
                  summarize:
                    path: scripts/summarize.py
                    runtime: python
                    description: Declared summary script.
                    argument_hint: text
                """, StandardCharsets.UTF_8);

        Map<String, SkillScriptDefinition> scripts = new SkillScriptDiscoverer()
                .discover(skill, options(), new SkillPathGuard());

        SkillScriptDefinition summarize = scripts.get("summarize");
        Assert.assertNotNull(summarize);
        Assert.assertEquals("scripts_yaml", summarize.getDeclarationSource());
        Assert.assertEquals("Declared summary script.", summarize.getDescription());
        Assert.assertEquals("text", summarize.getMetadata().get("argument_hint"));
    }

    @Test
    public void shouldParseScriptsYamlListDeclaration() throws Exception {
        Path skill = createSkillPackage("list-demo");
        Files.writeString(skill.resolve("scripts.yaml"), """
                scripts:
                  - name: summarize
                    path: scripts/summarize.py
                    runtime: python
                    description: List form summary script.
                """, StandardCharsets.UTF_8);

        Map<String, SkillScriptDefinition> scripts = new SkillScriptDiscoverer()
                .discover(skill, options(), new SkillPathGuard());

        SkillScriptDefinition summarize = scripts.get("summarize");
        Assert.assertNotNull(summarize);
        Assert.assertEquals("scripts/summarize.py", summarize.getRelativePath());
        Assert.assertEquals("python", summarize.getRuntime());
        Assert.assertEquals("scripts_yaml", summarize.getDeclarationSource());
    }

    @Test
    public void shouldDiscoverAllMasterStyleScriptRuntimes() throws Exception {
        Path skill = Files.createTempDirectory("skill-script-discoverer-runtimes-");
        Path scriptsDir = skill.resolve("scripts");
        Files.createDirectories(scriptsDir);
        Files.writeString(scriptsDir.resolve("python_demo.py"), "print('ok')\n", StandardCharsets.UTF_8);
        Files.writeString(scriptsDir.resolve("node_demo.js"), "console.log('ok');\n", StandardCharsets.UTF_8);
        Files.writeString(scriptsDir.resolve("shell_demo.sh"), "echo ok\n", StandardCharsets.UTF_8);
        Files.writeString(scriptsDir.resolve("powershell_demo.ps1"), "Write-Output ok\n", StandardCharsets.UTF_8);
        Files.writeString(scriptsDir.resolve("bat_demo.cmd"), "@echo off\necho ok\n", StandardCharsets.UTF_8);

        Map<String, SkillScriptDefinition> scripts = new SkillScriptDiscoverer()
                .discover(skill, allRuntimeOptions(), new SkillPathGuard());

        Assert.assertEquals("python", scripts.get("python_demo").getRuntime());
        Assert.assertEquals("node", scripts.get("node_demo").getRuntime());
        Assert.assertEquals("shell", scripts.get("shell_demo").getRuntime());
        Assert.assertEquals("powershell", scripts.get("powershell_demo").getRuntime());
        Assert.assertEquals("bat", scripts.get("bat_demo").getRuntime());
    }

    private Path createSkillPackage(String name) throws Exception {
        Path skill = Files.createTempDirectory("skill-script-discoverer-" + name + "-");
        Path scripts = skill.resolve("scripts");
        Files.createDirectories(scripts);
        // fixture 脚本内容不执行，只用于验证注册发现和路径边界。
        Files.writeString(scripts.resolve("summarize.py"), "print('ok')\n", StandardCharsets.UTF_8);
        return skill;
    }

    private SkillRuntimeOptions options() {
        return SkillRuntimeOptions.builder()
                .defaultScriptTimeoutSeconds(30)
                .maxScriptTimeoutSeconds(120)
                .allowedScriptRuntimes(List.of("python"))
                .build();
    }

    private SkillRuntimeOptions allRuntimeOptions() {
        return SkillRuntimeOptions.builder()
                .defaultScriptTimeoutSeconds(30)
                .maxScriptTimeoutSeconds(120)
                .allowedScriptRuntimes(List.of("python", "node", "shell", "powershell", "bat"))
                .build();
    }
}
