package com.flocier.test.domain.cli;

import com.flocier.domain.agent.browser.BrowserPairingView;
import com.flocier.domain.agent.browser.BrowserRelayApplicationService;
import com.flocier.domain.agent.adapter.port.BrowserRelaySocketPort;
import com.flocier.types.agent.config.BrowserRelayProperties;
import org.junit.Test;

import static org.junit.Assert.*;

public class BrowserRelayApplicationServiceTest {
    @Test public void pairingCanBeClaimedAndRevokedForLocalOwner() {
        BrowserRelayApplicationService service = new BrowserRelayApplicationService(new BrowserRelayProperties(), (BrowserRelaySocketPort) null);
        BrowserPairingView issued = service.createPairing("local-developer", "ws://localhost:8100/api/v1/browser/relay");
        assertEquals(6, issued.getCode().length());
        assertEquals("local-developer", service.resolveUserId(issued.getToken()));
        assertEquals(issued.getToken(), service.claim(issued.getCode()).getToken());
        service.revokeUser("local-developer");
        assertNull(service.resolveUserId(issued.getToken()));
    }
}
