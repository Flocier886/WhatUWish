package com.flocier.test.domain.skill;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import com.flocier.domain.agent.runtime.agent.tool.impl.SkillViewTool;
import com.flocier.domain.agent.runtime.agent.tool.impl.SkillsSearchTool;
import com.flocier.domain.agent.runtime.skill.SkillCatalogSearch;
import com.flocier.domain.agent.runtime.skill.SkillDefinition;
import com.flocier.domain.agent.runtime.skill.SkillPathGuard;
import com.flocier.domain.agent.runtime.skill.SkillPromptIndexBuilder;
import com.flocier.domain.agent.runtime.skill.SkillRegistry;
import com.flocier.domain.agent.runtime.skill.SkillRuntimeOptions;
import org.junit.Test;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.*;

public class SkillDiscoveryAlignmentTest {

    @Test
    public void searchFindsSkillBeyondOldListAndDoesNotExposeBody() {
        List<SkillDefinition> skills = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            skills.add(SkillDefinition.builder().name("alpha-" + i).description("ordinary task").build());
        }
        skills.add(SkillDefinition.builder().name("zeta-research").description("private source research")
                .category("research").tags(List.of("opencli"))
                .content("SECRET-INSTRUCTION-BODY").build());
        SkillRegistry registry = Mockito.mock(SkillRegistry.class);
        Mockito.when(registry.listSkills()).thenReturn(skills);
        SkillsSearchTool tool = new SkillsSearchTool(registry);
        ToolResultPayload result = (ToolResultPayload) tool.execute(request(Map.of("query", "opencli")),
                AgentContext.builder().build());
        assertTrue(result.isSuccess());
        assertTrue(result.getLlmObservation().contains("zeta-research"));
        assertFalse(result.getLlmObservation().contains("SECRET-INSTRUCTION-BODY"));
        assertTrue(String.valueOf(result.getStructuredOutput()).contains("research"));
        assertFalse(String.valueOf(result.getStructuredOutput()).contains("SECRET-INSTRUCTION-BODY"));
        assertEquals("zeta-research", SkillCatalogSearch.search(skills, "zeta-research", null, 10)
                .get(0).skill().getName());
    }

    @Test
    public void indexStaysWithinBudgetAndPointsToSearch() {
        List<SkillDefinition> skills = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            skills.add(SkillDefinition.builder().name("skill-" + i)
                    .description("long description for a large and searchable skill catalog " + i)
                    .content("SECRET-INSTRUCTION-BODY").build());
        }
        String index = SkillPromptIndexBuilder.build(skills);
        assertTrue(index.length() <= SkillPromptIndexBuilder.DEFAULT_MAX_CHARS);
        assertTrue(index.contains("skills_search"));
        assertTrue(index.contains("skill_view"));
        assertFalse(index.contains("SECRET-INSTRUCTION-BODY"));
        assertTrue(index.contains("<available_skills>"));
    }

    @Test
    public void viewDelegatesDocumentAndGuardsLinkedFile() throws Exception {
        Path root = Files.createTempDirectory("skill-view-");
        Files.writeString(root.resolve("SKILL.md"), "# Demo\nFollow these steps.", StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("references"));
        Files.writeString(root.resolve("references/guide.md"), "linked guide", StandardCharsets.UTF_8);
        SkillDefinition skill = SkillDefinition.builder().name("demo").description("demo skill")
                .basePath(root).content("# Demo\nFollow these steps.").build();
        SkillRegistry registry = Mockito.mock(SkillRegistry.class);
        Mockito.when(registry.listSkills()).thenReturn(List.of(skill));
        Mockito.when(registry.getRequiredSkill("demo")).thenReturn(skill);
        SkillViewTool view = new SkillViewTool(registry, new SkillPathGuard());
        ToolResultPayload document = (ToolResultPayload) view.execute(request(Map.of("name", "demo")),
                AgentContext.builder().build());
        assertTrue(document.isSuccess());
        assertTrue(document.getLlmObservation().contains("Follow these steps"));
        ToolResultPayload linked = (ToolResultPayload) view.execute(
                request(Map.of("name", "demo", "file_path", "references/guide.md")),
                AgentContext.builder().build());
        assertTrue(linked.isSuccess());
        assertTrue(linked.getLlmObservation().contains("linked guide"));
        assertTrue(linked.getLlmObservation().contains("references/guide.md"));
        ToolResultPayload escaped = (ToolResultPayload) view.execute(
                request(Map.of("name", "demo", "file_path", "../outside.txt")),
                AgentContext.builder().build());
        assertFalse(escaped.isSuccess());
    }

    @Test
    public void viewKeepsDocumentContentBeyondLegacyObservationPreview() {
        String body = "A".repeat(8500) + "FINAL-INSTRUCTION";
        SkillDefinition skill = SkillDefinition.builder().name("long-skill")
                .description("long skill").content(body).build();
        SkillRegistry registry = Mockito.mock(SkillRegistry.class);
        Mockito.when(registry.getRequiredSkill("long-skill")).thenReturn(skill);
        SkillViewTool view = new SkillViewTool(registry, new SkillPathGuard());
        ToolResultPayload result = (ToolResultPayload) view.execute(request(Map.of("name", "long-skill")),
                AgentContext.builder().build());
        assertTrue(result.isSuccess());
        assertTrue(result.getLlmObservation().contains("FINAL-INSTRUCTION"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void viewKeepsCompleteLinkedFile() throws Exception {
        Path root = Files.createTempDirectory("skill-view-limit-");
        Files.writeString(root.resolve("guide.md"), "X".repeat(200), StandardCharsets.UTF_8);
        SkillDefinition skill = SkillDefinition.builder().name("limited").description("limited")
                .basePath(root).content("# limited").build();
        SkillRegistry registry = Mockito.mock(SkillRegistry.class);
        Mockito.when(registry.getRequiredSkill("limited")).thenReturn(skill);
        SkillViewTool view = new SkillViewTool(registry, new SkillPathGuard());
        ToolResultPayload result = (ToolResultPayload) view.execute(request(Map.of(
                "name", "limited", "file_path", "guide.md")), AgentContext.builder().build());
        assertTrue(result.isSuccess());
        Map<String, Object> output = (Map<String, Object>) result.getStructuredOutput();
        assertEquals(200, ((String) output.get("content")).length());
        assertFalse(output.containsKey("truncated"));
    }

    private ToolInvokeRequest request(Map<String, Object> args) {
        return ToolInvokeRequest.builder().arguments(args).build();
    }
}
