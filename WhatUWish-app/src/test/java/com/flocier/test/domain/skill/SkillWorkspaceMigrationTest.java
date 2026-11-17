package com.flocier.test.domain.skill;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import com.flocier.domain.agent.runtime.agent.tool.impl.WorkspaceFileTool;
import com.flocier.domain.agent.runtime.skill.SkillPathGuard;
import com.flocier.domain.agent.runtime.skill.SkillRuntimeOptions;
import com.flocier.domain.agent.runtime.skill.SkillVirtualPaths;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class SkillWorkspaceMigrationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void workspaceToolsReadSearchAndEditVirtualSkillFiles() throws Exception {
        Path library = temporary.newFolder("skills").toPath();
        Path skill = Files.createDirectories(library.resolve("demo/references"));
        Files.writeString(skill.resolve("guide.md"), "first step\nsecond step\n");
        SkillVirtualPaths paths = paths(library);
        AgentContext context = AgentContext.builder().build();

        assertTrue(run("workspace_list", paths, context, Map.of("path", "skills/demo")).isSuccess());
        ToolResultPayload glob = run("workspace_glob", paths, context,
                Map.of("path", "skills/demo", "pattern", "**/*.md"));
        assertTrue(glob.getLlmObservation().contains("skills/demo/references/guide.md"));
        ToolResultPayload grep = run("workspace_grep", paths, context,
                Map.of("path", "skills/demo", "pattern", "second"));
        assertTrue(grep.getLlmObservation().contains("second step"));
        ToolResultPayload read = run("workspace_read", paths, context,
                Map.of("path", "skills/demo/references/guide.md"));
        assertTrue(read.getLlmObservation().contains("first step"));
        ToolResultPayload edit = run("workspace_edit", paths, context,
                Map.of("path", "skills/demo/references/guide.md", "old_string", "first", "new_string", "updated"));
        assertTrue(edit.getLlmObservation(), edit.isSuccess());
        assertTrue(Files.readString(skill.resolve("guide.md")).contains("updated step"));
        ToolResultPayload write = run("workspace_write", paths, context,
                Map.of("path", "skills/demo/references/new.md", "content", "new reference"));
        assertTrue(write.getLlmObservation(), write.isSuccess());
        assertEquals("new reference", Files.readString(skill.resolve("new.md")));
        assertFalse((Boolean) ((Map<?, ?>) write.getStructuredOutput()).get("registered"));
    }

    @Test public void virtualPathsRejectTraversal() throws Exception {
        Path library = temporary.newFolder("skills").toPath();
        SkillVirtualPaths paths = paths(library);
        AgentContext context = AgentContext.builder().build();
        for (String path : List.of("skills/../outside.txt", "skills/demo/../../outside.txt")) {
            ToolResultPayload result = run("workspace_write", paths, context,
                    Map.of("path", path, "content", "unsafe"));
            assertFalse(result.isSuccess());
        }
        assertFalse(Files.exists(library.getParent().resolve("outside.txt")));
    }

    private SkillVirtualPaths paths(Path library) {
        return new SkillVirtualPaths(SkillRuntimeOptions.builder().enabled(true)
                .directories(List.of(library.toString())).build(), new SkillPathGuard());
    }

    private ToolResultPayload run(String name, SkillVirtualPaths paths, AgentContext context, Map<String, Object> args) {
        WorkspaceFileTool tool = new WorkspaceFileTool(name, null, null, paths, null);
        return (ToolResultPayload) tool.execute(ToolInvokeRequest.builder().toolName(name).arguments(args).build(), context);
    }
}
