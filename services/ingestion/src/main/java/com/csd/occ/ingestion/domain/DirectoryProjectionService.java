package com.csd.occ.ingestion.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import java.util.UUID;

@Service
public class DirectoryProjectionService {
    private final OccUserRepository users;
    private final DirectorySynchronizationRepository checkpoints;
    private final Clock clock;
    private final ApplicationAuditEventRepository auditEvents;

    public DirectoryProjectionService(OccUserRepository users, DirectorySynchronizationRepository checkpoints, Clock clock,
            ApplicationAuditEventRepository auditEvents) {
        this.users = users; this.checkpoints = checkpoints; this.clock = clock; this.auditEvents = auditEvents;
    }

    /** Only called with the full provider snapshot, after all HTTP requests have completed. */
    @Transactional
    public Instant publish(Instant scanStartedAt, List<IdentityProviderPort.User> snapshot) {
        var checkpoint = checkpoints.lockCheckpoint();
        // Concurrent replicas may finish out of order. An earlier scan never replaces a later successful scan.
        if (checkpoint.scanStartedAt() != null && !scanStartedAt.isAfter(checkpoint.scanStartedAt()))
            return checkpoint.synchronizedAt();
        var existing = users.findAll().stream().collect(Collectors.toMap(OccUser::id, Function.identity()));
        Instant completedAt = clock.instant();
        for (var user : snapshot) {
            OccUser row = existing.remove(user.id());
            if (row == null) users.save(new OccUser(user, scanStartedAt, completedAt));
            else if (!row.observedAt().isAfter(scanStartedAt)) row.update(user, scanStartedAt, completedAt);
        }
        // A confirmed individual observation (for example provisioning) may finish during this full scan.
        // The older provider snapshot cannot hide or overwrite that newer observation.
        existing.values().stream().filter(user -> !user.observedAt().isAfter(scanStartedAt))
                .forEach(user -> user.markUnavailable(scanStartedAt, completedAt));
        checkpoint.complete(scanStartedAt, completedAt);
        String sourceKey = "directory-sync:" + scanStartedAt + ":SUCCESS";
        auditEvents.save(new ApplicationAuditEvent(UUID.randomUUID(), completedAt, completedAt, "ACCOUNT_ASSIGNMENT",
                "DIRECTORY_SYNCHRONIZATION", "SUCCESS", "INGESTION", null, "SYSTEM", "ACCOUNT_DIRECTORY", "SYNC",
                null, null, null, null, "Account directory synchronization completed",
                Map.of("accountCount", snapshot.size()), sourceKey));
        return completedAt;
    }
}
