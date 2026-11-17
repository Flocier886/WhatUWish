package com.flocier.application.agent.runtimeevent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeAgentCatalogTest {

    private final RuntimeAgentCatalog catalog = new RuntimeAgentCatalog();

    @Test
    void shouldPublishTheDatabaseIndependentReactRuntime() {
        RuntimeAgentDescriptor agent = catalog.listAvailableAgents().get(0);

        assertEquals(RuntimeAgentCatalog.REACT_RUNTIME_AGENT_ID, agent.getAgentId());
        assertEquals("REACT", agent.getRuntimeKind());
        assertTrue(agent.isAvailable());
        assertEquals(agent, catalog.requireAvailableAgent(agent.getAgentId()));
    }

    @Test
    void shouldRejectLegacyDatabaseAgentIdsAtRuntimeEventBoundary() {
        assertThrows(RuntimeException.class, () -> catalog.requireAvailableAgent("legacy-agent-id"));
    }
}
