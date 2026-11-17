package com.flocier.test.trigger;

import com.flocier.application.agent.file.ConversationFileSessionService;
import com.flocier.application.agent.runtimeevent.history.RuntimeHistoryQueryService;
import com.flocier.application.agent.runtimeevent.history.dto.RuntimeHistoryEventDTO;
import com.flocier.application.agent.runtimeevent.history.dto.RuntimeRunReplayDTO;
import com.flocier.domain.agent.runtime.event.ToolResultPayload;
import com.flocier.trigger.http.GenUiExportController;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.junit.Assert.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public class GenUiExportControllerTest {
    private MockMvc mvc;
    private ConversationFileSessionService sessions;
    private static final String TREE = "{\"runId\":\"run-1\",\"canvasId\":\"canvas-1\",\"tree\":{\"kind\":\"SectionHeader\",\"props\":{\"title\":\"中文导出\"}}}";

    @Before public void setUp() {
        sessions = mock(ConversationFileSessionService.class);
        RuntimeHistoryQueryService history = mock(RuntimeHistoryQueryService.class);
        var event = new RuntimeHistoryEventDTO("v1", "event-1", 1, "TOOL_RESULT", Instant.now(),
                "s1", "run-1", "request", "agent", new ToolResultPayload("tool-1", null, null, 1, null,
                "emit_ui_tree", "SUCCEEDED", "PERSISTED", "", "", null, null, List.of(),
                Map.of("kind", "genui.tree.v1", "data", Map.of("canvasId", "canvas-1"))));
        when(history.findRun("run-1")).thenReturn(Optional.of(new RuntimeRunReplayDTO("run-1", "s1",
                "request", "agent", "task", "COMPLETED", Instant.now(), Instant.now(), "", null,
                null, true, false, List.of(event))));
        mvc = MockMvcBuilders.standaloneSetup(new GenUiExportController(sessions, history)).build();
    }

    @Test public void exportsPdfAndDocxWithSessionCheck() throws Exception {
        for (String format : new String[]{"pdf", "docx"}) {
            var http = mvc.perform(post("/api/v1/genui/sessions/s1/export/" + format)
                    .contentType("application/json").content(TREE))
                    .andReturn().getResponse();
            org.junit.Assert.assertEquals(http.getContentAsString(), 200, http.getStatus());
            var response = http.getContentAsByteArray();
            assertTrue(response.length > 100);
            if ("pdf".equals(format)) assertEquals("%PDF", new String(response, 0, 4));
            else { assertEquals('P', response[0]); assertEquals('K', response[1]); }
        }
        verify(sessions, times(2)).requireAccess("s1");
    }

    @Test public void rejectsInvalidTreeAndFormat() throws Exception {
        mvc.perform(post("/api/v1/genui/sessions/s1/export/pdf")
                .contentType("application/json")
                .content("{\"runId\":\"run-1\",\"canvasId\":\"canvas-1\",\"tree\":{\"kind\":\"Missing\"}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/genui/sessions/s1/export/html")
                .contentType("application/json").content(TREE))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/genui/sessions/s1/export/pdf")
                .contentType("application/json")
                .content(TREE.replace("\"canvasId\":\"canvas-1\"", "\"canvasId\":\"\"")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/genui/sessions/s1/export/pdf")
                .contentType("application/json")
                .content(TREE.replace("\"tree\"", "\"mode\":\"unknown\",\"tree\"")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/genui/sessions/other/export/pdf")
                .contentType("application/json").content(TREE))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/genui/sessions/s1/export/pdf")
                .contentType("application/json")
                .content(TREE.replace("canvas-1", "invented")))
                .andExpect(status().isForbidden());
    }
}
