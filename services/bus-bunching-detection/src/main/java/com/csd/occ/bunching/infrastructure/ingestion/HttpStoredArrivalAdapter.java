package com.csd.occ.bunching.infrastructure.ingestion;

import com.csd.occ.bunching.domain.ArrivalSnapshot;
import com.csd.occ.bunching.domain.BunchingObservationRepository;
import com.csd.occ.bunching.domain.IngestionFeedCursor;
import com.csd.occ.bunching.domain.IngestionFeedCursorRepository;
import com.csd.occ.bunching.domain.SourceProcessingAudit;
import com.csd.occ.bunching.domain.SourceProcessingAuditRepository;
import com.csd.occ.bunching.domain.SourceRejection;
import com.csd.occ.bunching.domain.SourceRejectionRepository;
import com.csd.occ.bunching.domain.SourceRetry;
import com.csd.occ.bunching.domain.SourceRetryRepository;
import com.csd.occ.bunching.domain.StoredArrivalPort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

@Component
public class HttpStoredArrivalAdapter implements StoredArrivalPort {
    private final RestClient ingestion;
    private final IngestionFeedCursorRepository cursor;
    private final SourceRetryRepository retries;
    private final SourceRejectionRepository rejections;
    private final SourceProcessingAuditRepository audit;
    private final BunchingObservationRepository observations;
    private final Map<UUID, Long> discoveredSequences = new HashMap<>();

    public HttpStoredArrivalAdapter(RestClient ingestionRestClient, IngestionFeedCursorRepository cursor,
                                    SourceRetryRepository retries, SourceRejectionRepository rejections,
                                    SourceProcessingAuditRepository audit, BunchingObservationRepository observations) {
        this.ingestion = ingestionRestClient;
        this.cursor = cursor;
        this.retries = retries;
        this.rejections = rejections;
        this.audit = audit;
        this.observations = observations;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> pendingIds(int limit, Instant now) {
        if (limit < 1 || limit > 1_000) throw new IllegalArgumentException("Limit must be 1..1000");
        List<UUID> ids = new ArrayList<>();
        retries.findByNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(now, PageRequest.of(0, limit)).forEach(retry ->
                ids.add(retry.rawObservationId()));
        if (ids.size() == limit) return ids;

        long after = cursor.findById(1).orElseThrow().lastSequence();
        FeedPage page = ingestion.get().uri(builder -> builder.path("/internal/v1/arrival-snapshots")
                .queryParam("afterSequence", after).queryParam("size", limit - ids.size()).build())
                .retrieve().body(FeedPage.class);
        if (page == null) throw new IllegalStateException("Ingestion feed returned no body");
        for (Snapshot source : page.items()) {
            discoveredSequences.put(source.rawObservationId(), source.sequence());
            if (!observations.findByRawObservationId(source.rawObservationId()).isPresent()
                    && !rejections.existsById(source.rawObservationId()) && !ids.contains(source.rawObservationId())) {
                ids.add(source.rawObservationId());
            }
        }
        return List.copyOf(ids);
    }

    @Override
    public ArrivalSnapshot snapshot(UUID id) {
        Snapshot source = ingestion.get().uri("/internal/v1/arrival-snapshots/{id}", id).retrieve().body(Snapshot.class);
        if (source == null) throw new IllegalStateException("Ingestion snapshot returned no body");
        discoveredSequences.put(id, source.sequence());
        if (!"NORMALIZED".equals(source.normalizationState())) {
            throw new com.csd.occ.bunching.domain.PendingArrivalException();
        }
        Optional<UUID> reference = source.arrivals().stream().map(Arrival::referenceVersionId).distinct().reduce(
                (first, second) -> { throw new com.csd.occ.bunching.domain.InvalidArrivalException("MIXED_REFERENCE_VERSIONS"); });
        return new ArrivalSnapshot(source.rawObservationId(), source.pollRunId(), reference, source.fetchedAt(),
                source.arrivals().stream().map(arrival -> new ArrivalSnapshot.Arrival(arrival.observationId(),
                        arrival.serviceId(), arrival.stopCode(), Optional.ofNullable(arrival.directionId()),
                        arrival.visitOrder(), Optional.ofNullable(arrival.estimatedArrivalAt()))).toList());
    }

    @Override
    @Transactional
    public void reject(UUID id, Instant rejectedAt, String reason) {
        if (!rejections.existsById(id)) {
            rejections.save(new SourceRejection(id, rejectedAt, reason));
            audit.save(new SourceProcessingAudit(id, rejectedAt, "SOURCE_REJECTED", reason, null));
        }
        retries.deleteById(id);
        acknowledge(id);
    }

    @Override
    @Transactional
    public void defer(UUID id, Instant failedAt, Instant nextAttemptAt, RetryReason reason) {
        retries.findById(id).ifPresentOrElse(retry -> retry.deferUntil(nextAttemptAt),
                () -> retries.save(new SourceRetry(id, nextAttemptAt)));
        audit.save(new SourceProcessingAudit(id, failedAt, "SOURCE_DEFERRED", reason.name(), nextAttemptAt));
        acknowledge(id);
    }

    @Override
    @Transactional
    public void complete(UUID id) {
        retries.deleteById(id);
        acknowledge(id);
    }

    @Override
    @Transactional
    public void acknowledge(UUID id) {
        Long sequence = discoveredSequences.remove(id);
        if (sequence == null) return;
        IngestionFeedCursor checkpoint = cursor.findById(1).orElseThrow();
        checkpoint.advanceTo(sequence);
    }

    private record FeedPage(List<Snapshot> items, long nextSequence) {
        private FeedPage { items = List.copyOf(items); }
    }

    private record Snapshot(long sequence, UUID rawObservationId, UUID pollRunId, String sourceKey, Instant fetchedAt,
                            String normalizationState, List<Arrival> arrivals) {
        private Snapshot { arrivals = List.copyOf(arrivals); }
    }

    private record Arrival(UUID observationId, UUID referenceVersionId, String stopCode, String serviceId,
                           Integer directionId, int visitOrder, Instant estimatedArrivalAt) {
    }
}
