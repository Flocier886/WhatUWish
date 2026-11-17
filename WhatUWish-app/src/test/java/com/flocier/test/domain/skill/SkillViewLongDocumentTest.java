package com.flocier.test.domain.skill;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolObservationBudget;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import com.flocier.domain.agent.runtime.agent.tool.impl.SkillViewTool;
import com.flocier.domain.agent.runtime.agent.tool.impl.WorkspaceFileTool;
import com.flocier.domain.agent.runtime.file.RunWorkspaceService;
import com.flocier.domain.agent.runtime.skill.SkillDefinition;
import com.flocier.domain.agent.runtime.skill.SkillPathGuard;
import com.flocier.domain.agent.runtime.skill.SkillRegistry;
import org.junit.Test;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.Assert.*;

public class SkillViewLongDocumentTest {
    @Test
    public void fullSkillSurvivesObservationBudgetAndWorkspaceContinuation() throws Exception {
        Path root = Files.createTempDirectory("skill-observation-");
        StringBuilder body = new StringBuilder();
        for (int i = 1; i <= 400; i++) body.append("line ").append(i).append(' ')
                .append("instruction ".repeat(8)).append('\n');
        body.append("FINAL-INSTRUCTION");
        SkillDefinition skill = SkillDefinition.builder().name("long-skill")
                .description("long skill").content(body.toString()).build();
        SkillRegistry registry = Mockito.mock(SkillRegistry.class);
        Mockito.when(registry.getRequiredSkill("long-skill")).thenReturn(skill);
        ToolResultPayload full = (ToolResultPayload) new SkillViewTool(registry, new SkillPathGuard())
                .execute(ToolInvokeRequest.builder().arguments(Map.of("name", "long-skill")).build(),
                        AgentContext.builder().build());
        assertTrue(full.isSuccess());
        assertTrue(full.getLlmObservation().contains("FINAL-INSTRUCTION"));

        AgentContext context = AgentContext.builder().sessionId("test").build();
        context.setAttribute(RunWorkspaceService.ROOT_ATTRIBUTE, root);
        String envelope = ToolObservationBudget.apply(context, "skill_view", full.getLlmObservation());
        assertTrue(envelope.contains("<persisted-output>"));
        assertFalse(envelope.contains("FINAL-INSTRUCTION"));
        String relative = envelope.substring(envelope.indexOf("Full output saved to: ") + 22)
                .split("\n", 2)[0];
        assertEquals(full.getLlmObservation(), Files.readString(root.resolve(relative)));

        RunWorkspaceService workspace = Mockito.mock(RunWorkspaceService.class);
        Mockito.when(workspace.readable(Mockito.eq(context), Mockito.eq(relative)))
                .thenReturn(root.resolve(relative));
        Mockito.when(workspace.relative(Mockito.eq(context), Mockito.eq(root.resolve(relative))))
                .thenReturn(relative);
        WorkspaceFileTool reader = new WorkspaceFileTool("workspace_read", workspace, null);
        ToolResultPayload continued = (ToolResultPayload) reader.execute(ToolInvokeRequest.builder()
                .arguments(Map.of("path", relative, "start_line", 390, "line_count", 20)).build(), context);
        assertTrue(continued.getLlmObservation(), continued.isSuccess());
        assertTrue(continued.getLlmObservation().contains("FINAL-INSTRUCTION"));
    }
}
