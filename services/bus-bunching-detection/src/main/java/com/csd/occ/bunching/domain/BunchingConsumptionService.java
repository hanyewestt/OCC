package com.csd.occ.bunching.domain;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class BunchingConsumptionService {
    private static final Duration RETRY_DELAY = Duration.ofSeconds(30);
    private final StoredArrivalPort source;
    private final BunchingRecordingService recording;
    private final Clock clock;

    public BunchingConsumptionService(StoredArrivalPort source, BunchingRecordingService recording, Clock clock) {
        this.source = source;
        this.recording = recording;
        this.clock = clock;
    }

    // Each recording call has its own transaction. A failed row never advances a global cursor.
    public BatchResult consume(int limit, Duration threshold, Duration staleAfter) {
        int recorded = 0;
        int duplicates = 0;
        int rejected = 0;
        var failures = new ArrayList<UUID>();
        for (var id : source.pendingIds(limit, clock.instant())) {
            try {
                try {
                    var result = recording.record(source.snapshot(id), threshold, staleAfter);
                    if (result.outcome() == RecordedBunching.Outcome.RECORDED) recorded++;
                    else duplicates++;
                    source.acknowledge(id);
                } catch (InvalidArrivalException invalid) {
                    source.reject(id, clock.instant(), invalid.getMessage());
                    rejected++;
                }
            } catch (RuntimeException failure) {
                // Persist a cooldown so failed rows cannot occupy every batch on every cycle.
                // If the database cannot store the retry, fail this cycle visibly; do not claim it was deferred.
                var failedAt = clock.instant();
                source.defer(id, failedAt, failedAt.plus(RETRY_DELAY), failure instanceof PendingArrivalException
                        ? StoredArrivalPort.RetryReason.NORMALIZATION_PENDING : StoredArrivalPort.RetryReason.PROCESSING_FAILED);
                failures.add(id);
            }
        }
        return new BatchResult(recorded, duplicates, rejected, failures);
    }

    public record BatchResult(int recorded, int duplicates, int rejected, List<UUID> failedObservationIds) {
        public BatchResult {
            failedObservationIds = List.copyOf(failedObservationIds);
        }
    }
}
