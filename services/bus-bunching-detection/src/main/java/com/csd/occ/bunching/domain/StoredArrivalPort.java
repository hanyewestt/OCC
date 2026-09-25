package com.csd.occ.bunching.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Persisted ingestion input only. Implementations never fetch upstream telemetry. */
public interface StoredArrivalPort {
    List<UUID> pendingIds(int limit, Instant now);
    ArrivalSnapshot snapshot(UUID rawObservationId);
    void reject(UUID rawObservationId, Instant rejectedAt, String reason);
    void defer(UUID rawObservationId, Instant failedAt, Instant nextAttemptAt, RetryReason reason);
    void complete(UUID rawObservationId);
    void acknowledge(UUID rawObservationId);

    enum RetryReason { NORMALIZATION_PENDING, PROCESSING_FAILED }
}
