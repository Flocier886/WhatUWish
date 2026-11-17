package com.flocier.domain.agent.runtime.desktopcontrol;

public interface DesktopSessionPort {
    OpenedDesktop open(String requestId, String sessionId, String ownerKey, int ttlSeconds);
    void close(String requestId, String sessionId, String ownerKey);

    record OpenedDesktop(String url, double holdUntil) { }
}
