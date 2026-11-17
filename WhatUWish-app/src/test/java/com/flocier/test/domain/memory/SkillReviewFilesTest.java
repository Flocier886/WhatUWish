package com.flocier.test.domain.memory;

import com.flocier.domain.agent.runtime.agent.*;
import com.flocier.domain.agent.runtime.agent.tool.*;
import com.flocier.domain.agent.runtime.agent.tool.impl.*;
import com.flocier.domain.agent.runtime.memory.review.*;
import com.flocier.domain.agent.runtime.skill.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** 使用真实临时文件验证写入与发现闭环，不连接服务、不修改用户 Skill。 */
public class SkillReviewFilesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Path root;
    private SkillRuntimeOptions options;
    private DefaultSkillRegistry registry;
    private SkillReviewFiles files;
    private AgentContext context;
    private final SkillPathGuard guard = new SkillPathGuard();
    private final String document = "---\nname: review-fixture\ndescription: 可复用流程测试\n---\n# 操作流程\n重复 重复\n";

    @Before public void setUp() throws Exception {
        root = temporary.newFolder("skills").toPath();
        options = SkillRuntimeOptions.builder().enabled(true).reactEnabled(true).masterStyleToolsEnabled(true)
                .directories(List.of(root.toString())).maxReadChars(16000).maxListEntries(50).maxSearchMatches(50)
                .allowFullContentRead(true).build();
        registry = new DefaultSkillRegistry(options, new DefaultSkillParser(new SkillYamlParser(), new SkillMarkdownParser()), guard);
        registry.refresh();
        files = new SkillReviewFiles(registry, options, guard);
        context = AgentContext.builder().runMode(AgentRunMode.SKILL_REVIEW).skillReviewState(new SkillReviewState()).build();
    }

    @Test public void createsUnregisteredPackageEditsAndRefreshesForMainDiscovery() throws Exception {
        write("SKILL.md", document);
        write("references/guide.md", "操作步骤 A");
        assertFalse(registry.findSkill("review-fixture").isPresent());
        ToolCollection tools = pathTools();
        for (String name : List.of("skill_read_tool", "skill_list_directory_tool", "skill_glob_tool", "skill_grep_tool")) {
            Map<String, Object> args = new HashMap<>(Map.of("skill_name", "review-fixture", "path", name.equals("skill_read_tool") ? "SKILL.md" : ".", "pattern", name.equals("skill_grep_tool") ? "操作" : "**/*.md"));
            ToolResultPayload result = (ToolResultPayload) tools.getRequired(name).execute(request(name, args), context);
            assertTrue(name + ": " + result.getLlmObservation(), result.isSuccess());
        }
        edit("SKILL.md", "操作流程", "完整操作流程", false);
        edit("SKILL.md", "完整操作流程", "稳定操作流程", false);
        assertEquals(2, context.getSkillReviewState().changes().size());
        assertEquals("SUCCESS", files.refresh(context.getSkillReviewState()).status());
        ToolResultPayload discovered = (ToolResultPayload) new SkillTool("skill_tool", "skill", Map.of(), registry, options)
                .execute(request("skill_tool", Map.of()), AgentContext.builder().build());
        assertTrue(discovered.getLlmObservation().contains("review-fixture"));
        ToolResultPayload mainRead = (ToolResultPayload) tools.getRequired("skill_read_tool").execute(
                request("skill_read_tool", Map.of("skill_name", "review-fixture", "path", "SKILL.md")), AgentContext.builder().build());
        assertTrue(mainRead.isSuccess());
        assertTrue(mainRead.getLlmObservation().contains("稳定操作流程"));
    }

    @Test public void requiresReadDetectsConflictAndSupportsReplaceAll() throws Exception {
        Path path = root.resolve("review-fixture/SKILL.md");
        write("SKILL.md", document);
        AgentContext other = AgentContext.builder().runMode(AgentRunMode.SKILL_REVIEW).skillReviewState(new SkillReviewState()).build();
        assertThrows(IllegalArgumentException.class, () -> files.write(other, editArgs("SKILL.md", "操作", "步骤", false), true));
        SkillReviewFiles.read(other, path);
        write("SKILL.md", document + "外部修改\n");
        assertThrows(IllegalArgumentException.class, () -> files.write(other, editArgs("SKILL.md", "操作", "步骤", false), true));
        assertThrows(IllegalArgumentException.class, () -> edit("SKILL.md", "重复", "替换", false));
        assertEquals(2, edit("SKILL.md", "重复", "替换", true).get("replacements"));
        assertTrue(Files.readString(path).contains("替换 替换"));
        assertThrows(IllegalArgumentException.class, () -> edit("SKILL.md", "不存在", "替换", false));
    }

    @Test public void noChangeAndInvalidPackageAreReportedSeparatelyFromWriteSuccess() throws Exception {
        write("SKILL.md", document);
        SkillReviewState fresh = new SkillReviewState();
        context.setSkillReviewState(fresh);
        SkillReviewFiles.read(context, root.resolve("review-fixture/SKILL.md"));
        assertEquals("unchanged", write("SKILL.md", document).get("changeType"));
        assertEquals("NOT_NEEDED", files.refresh(fresh).status());
        write("SKILL.md", "missing frontmatter");
        assertEquals("INVALID", files.refresh(fresh).status());
        assertEquals(1, fresh.changes().size());
    }

    @Test public void rejectsEscapeOversizeDirectoriesMainModeAndCancelledWrites() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> write("../outside.txt", "禁止"));
        assertThrows(IllegalArgumentException.class, () -> write(root.resolve("absolute.txt").toString(), "禁止"));
        assertThrows(IllegalArgumentException.class, () -> write("huge.txt", "a".repeat(SkillReviewFiles.MAX_WRITE_CHARS + 1)));
        Files.createDirectories(root.resolve("review-fixture/directory"));
        assertThrows(IllegalArgumentException.class, () -> write("directory", "禁止"));
        assertThrows(IllegalArgumentException.class, () -> files.write(AgentContext.builder().build(), Map.of(), false));
        write("references/partial.md", "已保存的部分结果");
        context.getSkillReviewState().cancel();
        assertThrows(java.util.concurrent.CancellationException.class, () -> write("late.txt", "禁止"));
        assertFalse(Files.exists(root.resolve("review-fixture/late.txt")));
        assertEquals("INVALID", files.refresh(context.getSkillReviewState()).status());
        assertEquals(1, context.getSkillReviewState().changes().size());
    }

    @Test public void refreshFailurePreservesActualChanges() throws Exception {
        write("SKILL.md", document);
        SkillRegistry broken = mock(SkillRegistry.class);
        doThrow(new IllegalStateException("刷新失败")).when(broken).refresh();
        assertEquals("FAILED", new SkillReviewFiles(broken, options, guard).refresh(context.getSkillReviewState()).status());
        assertEquals(1, context.getSkillReviewState().changes().size());
    }

    @Test public void rejectsJunctionOrSymlinkOutsidePackage() throws Exception {
        Path outside = temporary.newFolder("outside").toPath();
        Path link = root.resolve("review-fixture/escape");
        Files.createDirectories(link.getParent());
        if (System.getProperty("os.name").toLowerCase().contains("windows")) {
            Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), outside.toString()).redirectErrorStream(true).start();
            assertEquals(new String(process.getInputStream().readAllBytes()), 0, process.waitFor());
        } else Files.createSymbolicLink(link, outside);
        try {
            assertThrows(IllegalArgumentException.class, () -> write("escape/stolen.txt", "禁止"));
            assertThrows(IllegalArgumentException.class, () -> guard.assertPathUnderRoot(link.resolve("stolen.txt"), link.getParent()));
            assertFalse(Files.exists(outside.resolve("stolen.txt")));
        } finally { Files.delete(link); }
    }

    private Map<String, Object> write(String path, String content) throws Exception {
        return files.write(context, Map.of("skill_name", "review-fixture", "path", path, "content", content), false);
    }
    private Map<String, Object> edit(String path, String old, String next, boolean all) throws Exception {
        return files.write(context, editArgs(path, old, next, all), true);
    }
    private Map<String, Object> editArgs(String path, String old, String next, boolean all) {
        return Map.of("skill_name", "review-fixture", "path", path, "old_string", old, "new_string", next, "replace_all", all);
    }
    private ToolInvokeRequest request(String name, Map<String, Object> args) { return ToolInvokeRequest.builder().toolName(name).arguments(args).build(); }
    private ToolCollection pathTools() {
        return new ToolCollection(List.of(new SkillReadTool("skill_read_tool", "read", Map.of(), registry, options, guard),
                new SkillListDirectoryTool("skill_list_directory_tool", "list", Map.of(), registry, options, guard),
                new SkillGlobTool("skill_glob_tool", "glob", Map.of(), registry, options, guard),
                new SkillGrepTool("skill_grep_tool", "grep", Map.of(), registry, options, guard)));
    }
}
