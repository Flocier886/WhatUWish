package com.flocier.test.domain.desktopcontrol;

import com.flocier.application.agent.desktopcontrol.DesktopControlApplicationService;
import com.flocier.domain.agent.runtime.desktopcontrol.DesktopControlRecord;
import com.flocier.domain.agent.runtime.desktopcontrol.DesktopControlStatuses;
import com.flocier.domain.agent.runtime.desktopcontrol.DesktopSessionPort;
import com.flocier.infrastructure.persistent.memory.InMemoryDesktopControlRepository;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class DesktopControlApplicationServiceTest {
    @Test
    public void completeIsIdempotentAndResumeCanOnlyBeClaimedOnce() {
        InMemoryDesktopControlRepository repository = new InMemoryDesktopControlRepository();
        DesktopSessionPort desktop = mock(DesktopSessionPort.class);
        DesktopControlApplicationService service = new DesktopControlApplicationService(repository, desktop);
        repository.insert(pending("dc_test", "session-a", System.currentTimeMillis() / 1000d + 600));

        DesktopControlRecord first = service.complete("session-a", "dc_test");
        DesktopControlRecord second = service.complete("session-a", "dc_test");
        assertEquals(DesktopControlStatuses.RESUME_PENDING, first.getStatus());
        assertEquals(first.getResumeRequestId(), second.getResumeRequestId());
        assertEquals(first.getResumeRunId(), second.getResumeRunId());
        service.claimResume("session-a", first.getResumeRequestId());
        try {
            service.claimResume("session-a", first.getResumeRequestId());
            fail("同一个续跑不能再次认领");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("认领"));
        }
        service.finish("dc_test", true);
        assertEquals(DesktopControlStatuses.COMPLETED,
                repository.findByControlId("dc_test").orElseThrow().getStatus());
        verify(desktop, atLeastOnce()).close("request-a", "session-a", "code:session-a");
    }

    @Test
    public void crossSessionAndExpiredRequestsCannotResume() {
        InMemoryDesktopControlRepository repository = new InMemoryDesktopControlRepository();
        DesktopControlApplicationService service = new DesktopControlApplicationService(repository, mock(DesktopSessionPort.class));
        repository.insert(pending("dc_expired", "session-a", System.currentTimeMillis() / 1000d - 1));
        try {
            service.complete("session-b", "dc_expired");
            fail("跨会话不应允许完成");
        } catch (IllegalArgumentException expected) {
            assertEquals("会话不匹配", expected.getMessage());
        }
        try {
            service.complete("session-a", "dc_expired");
            fail("过期接管不应允许完成");
        } catch (IllegalStateException expected) {
            assertEquals(DesktopControlStatuses.EXPIRED,
                    repository.findByControlId("dc_expired").orElseThrow().getStatus());
        }
    }

    private DesktopControlRecord pending(String id, String sessionId, double holdUntil) {
        DesktopControlRecord record = new DesktopControlRecord();
        record.setControlId(id);
        record.setSessionId(sessionId);
        record.setSourceRequestId("request-a");
        record.setOwnerKey("code:" + sessionId);
        record.setHoldUntil(holdUntil);
        record.setStatus(DesktopControlStatuses.PENDING);
        return record;
    }
}
