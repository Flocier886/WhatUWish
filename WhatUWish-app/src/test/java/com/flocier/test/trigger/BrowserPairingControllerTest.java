package com.flocier.test.trigger;

import com.flocier.domain.agent.browser.BrowserPairingView;
import com.flocier.domain.agent.browser.BrowserRelayApplicationService;
import com.flocier.domain.agent.browser.LocalBrowserOwner;
import com.flocier.trigger.http.browser.BrowserPairingController;
import com.flocier.types.agent.config.BrowserRelayProperties;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class BrowserPairingControllerTest {
    private MockMvc mockMvc;

    @Before
    public void setUp() {
        BrowserRelayApplicationService service = mock(BrowserRelayApplicationService.class);
        BrowserPairingView pairing = BrowserPairingView.builder()
                .token("token-1").code("123456")
                .relayUrl("ws://127.0.0.1:8091/api/v1/browser/relay")
                .expiresAt(123L).build();
        when(service.claim("123456")).thenReturn(pairing);
        BrowserRelayProperties properties = new BrowserRelayProperties();
        mockMvc = MockMvcBuilders.standaloneSetup(
                new BrowserPairingController(service, properties, new LocalBrowserOwner(properties))).build();
    }

    @Test
    public void shouldClaimFromWebApiAndPublishedExtensionPopupPath() throws Exception {
        for (String path : new String[] {"/api/v1/browser/claim", "/api/agent/browser/claim"}) {
            mockMvc.perform(post(path).contentType("application/json").content("{\"code\":\"123456\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.token").value("token-1"))
                    .andExpect(jsonPath("$.data.relayUrl").value("ws://127.0.0.1:8091/api/v1/browser/relay"));
        }
    }

    @Test
    public void shouldAllowLocalWorkbenchCrossOriginRequest() throws Exception {
        mockMvc.perform(post("/api/v1/browser/claim")
                        .contentType("application/json")
                        .content("{\"code\":\"123456\"}")
                        .header("Origin", "http://127.0.0.1:5173"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "*"));
    }
}
