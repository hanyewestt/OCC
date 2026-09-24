package com.csd.occ.ingestion.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ArrivalTelemetryPort {
    List<ArrivalPollTarget> targets();
    Optional<ArrivalPoll> claim(ArrivalPollTarget target, Instant now, Duration lease);
    boolean complete(ArrivalPoll poll, ArrivalFetch fetch, Instant fetchedAt, int staleAfterSeconds);
    List<UUID> expiredIds(Instant now, int limit);
    boolean recoverExpired(UUID pollRunId, Instant now);
}
