package com.csd.occ.ingestion.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DirectoryAuditCaptureService implements DirectoryAuditCapturePort {
    private final ApplicationAuditEventRepository events;
    private final Clock clock;

    public DirectoryAuditCaptureService(ApplicationAuditEventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failure(Instant startedAt, String safeReason) {
        String sourceKey = "directory-sync:" + startedAt + ":FAILURE";
        if (events.existsBySourceKey(sourceKey)) return;
        Instant now = clock.instant();
        events.save(new ApplicationAuditEvent(UUID.randomUUID(), startedAt, now, "ACCOUNT_ASSIGNMENT",
                "DIRECTORY_SYNCHRONIZATION", "FAILURE", "INGESTION", null, "SYSTEM", "ACCOUNT_DIRECTORY", "SYNC",
                null, null, null, null, "Account directory synchronization failed",
                Map.of("reason", safeReason), sourceKey));
    }
}
