package com.flocier.test.domain.cli;

import com.flocier.domain.agent.adapter.port.AgentBrowserSession;
import com.flocier.infrastructure.adapter.port.AgentBrowserSessionAdapter;
import com.flocier.infrastructure.adapter.repository.AgentBrowserSessionPersistenceService;
import com.flocier.infrastructure.dao.IAiAgentBrowserSessionDao;
import com.flocier.types.agent.config.AgentBrowserProperties;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class AgentBrowserSessionAdapterTest {
    @Test public void missingKeyDoesNotUseDatabaseOrKernel() {
        AgentBrowserProperties properties = new AgentBrowserProperties();
        IAiAgentBrowserSessionDao dao = mock(IAiAgentBrowserSessionDao.class);
        AgentBrowserSessionAdapter.AgentBrowserHttpClient http = mock(AgentBrowserSessionAdapter.AgentBrowserHttpClient.class);
        AgentBrowserSessionAdapter adapter = new AgentBrowserSessionAdapter(properties, dao, http);
        assertFalse(adapter.isConfigured());
        try { adapter.resolveForUser("local-developer"); fail("expected disabled session"); }
        catch (IllegalStateException expected) { assertFalse(expected.getMessage().contains("secret")); }
        verifyNoInteractions(dao, http);
    }

    @Test public void firstResolveCreatesStableMappingWithoutPersistingCdp() {
        AgentBrowserProperties properties = new AgentBrowserProperties();
        properties.setEnabled(true);
        properties.setApiKey("test-key");
        IAiAgentBrowserSessionDao dao = mock(IAiAgentBrowserSessionDao.class);
        AgentBrowserSessionPersistenceService persistence = mock(AgentBrowserSessionPersistenceService.class);
        AgentBrowserSessionAdapter.AgentBrowserHttpClient http = mock(AgentBrowserSessionAdapter.AgentBrowserHttpClient.class);
        when(http.createBrowser(anyString(), anyInt(), eq(false), eq("agent"), eq(false)))
                .thenReturn(new AgentBrowserSessionAdapter.AgentBrowserHttpResponse(200,
                        "{\"session_id\":\"remote-1\",\"name\":\"rb-test\",\"cdp_ws_url\":\"wss://kernel/cdp\",\"browser_live_view_url\":\"https://kernel/live\"}"));
        AgentBrowserSessionAdapter adapter = new AgentBrowserSessionAdapter(properties, dao, persistence, http);
        AgentBrowserSession session = adapter.resolveForUser("local-developer");
        assertEquals("remote-1", session.getAgentSessionId());
        assertEquals("wss://kernel/cdp", session.getCdpWsUrl());
        verify(persistence).upsertMapping(eq("local-developer"), eq("remote-1"), eq("rb-test"), any());
    }
}
