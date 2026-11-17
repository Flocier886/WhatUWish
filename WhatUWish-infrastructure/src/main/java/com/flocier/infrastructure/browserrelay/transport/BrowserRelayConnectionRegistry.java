package com.flocier.infrastructure.browserrelay.transport;

import com.flocier.domain.agent.browser.model.BrowserRelayConnection;

/**
 * Infrastructure-only registry for live relay channels.
 */
public interface BrowserRelayConnectionRegistry {

    BrowserRelayConnection replace(BrowserRelayConnection connection);

    BrowserRelayConnection current(String userId);

    BrowserRelayConnection remove(String userId, String connectionId);
}
