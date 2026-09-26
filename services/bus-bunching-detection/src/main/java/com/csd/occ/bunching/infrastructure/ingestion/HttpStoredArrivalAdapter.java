package com.csd.occ.bunching.infrastructure.ingestion;

import com.csd.occ.bunching.domain.ArrivalSnapshot;
import com.csd.occ.bunching.domain.BunchingObservationRepository;
import com.csd.occ.bunching.domain.ConsumerSelection;
import com.csd.occ.bunching.domain.ConsumerSelectionService;
import com.csd.occ.bunching.domain.IngestionFeedCursor;
import com.csd.occ.bunching.domain.IngestionFeedCursorRepository;
import com.csd.occ.bunching.domain.IngestionFeedHeadPort;
import com.csd.occ.bunching.domain.SourceProcessingAudit;
import com.csd.occ.bunching.domain.SourceProcessingAuditRepository;
import com.csd.occ.bunching.domain.SourceRejection;
import com.csd.occ.bunching.domain.SourceRejectionRepository;
import com.csd.occ.bunching.domain.SourceRetry;
import com.csd.occ.bunching.domain.SourceRetryRepository;
import com.csd.occ.bunching.domain.StoredArrivalPort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ConsumerSelectionService selection;
    private final IngestionFeedHeadPort liveHead;
    private final Map<UUID, Long> discoveredSequences = new ConcurrentHashMap<>();
    private final Map<UUID, FeedIdentity> discoveredFeeds = new ConcurrentHashMap<>();

    @Autowired
    public HttpStoredArrivalAdapter(RestClient ingestionRestClient, IngestionFeedCursorRepository cursor,
                                    SourceRetryRepository retries, SourceRejectionRepository rejections,
                                    SourceProcessingAuditRepository audit, BunchingObservationRepository observations,
                                    ConsumerSelectionService selection, IngestionFeedHeadPort liveHead) {
        this.ingestion = ingestionRestClient; this.cursor = cursor; this.retries = retries;
        this.rejections = rejections; this.audit = audit; this.observations = observations;
        this.selection = selection; this.liveHead = liveHead;
    }

    /** Compatibility constructor for focused direct adapter tests. */
    public HttpStoredArrivalAdapter(RestClient ingestionRestClient, IngestionFeedCursorRepository cursor,
                                    SourceRetryRepository retries, SourceRejectionRepository rejections,
                                    SourceProcessingAuditRepository audit, BunchingObservationRepository observations) {
        this.ingestion = ingestionRestClient; this.cursor = cursor; this.retries = retries;
        this.rejections = rejections; this.audit = audit; this.observations = observations;
        this.selection = null; this.liveHead = null;
    }

    @Override
    @Transactional
    public List<UUID> pendingIds(int limit, Instant now) {
        if (limit < 1 || limit > 1_000) throw new IllegalArgumentException("Limit must be 1..1000");
        FeedIdentity feed = selectedFeed();
        IngestionFeedCursor checkpoint = cursor.findById(1).orElseThrow();
        align(checkpoint, feed);

        List<UUID> ids = new ArrayList<>();
        retries.findByFeedScopeAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                feed.feedScope(), now, PageRequest.of(0, limit)).forEach(retry -> {
            discoveredFeeds.put(retry.rawObservationId(), identity(retry));
            if (!observations.findByRawObservationId(retry.rawObservationId()).isPresent()
                    && !rejections.existsById(retry.rawObservationId())) ids.add(retry.rawObservationId());
            else retries.deleteById(retry.rawObservationId());
        });
        if (ids.size() == limit) return List.copyOf(ids);

        FeedPage page = ingestion.get().uri(builder -> builder.path(feed.pagePath())
                .queryParam("afterSequence", checkpoint.lastSequence()).queryParam("size", limit - ids.size()).build())
                .retrieve().body(FeedPage.class);
        if (page == null) throw new IllegalStateException("Ingestion feed returned no body");
        for (Snapshot source : page.items()) {
            discoveredSequences.put(source.rawObservationId(), source.sequence());
            discoveredFeeds.put(source.rawObservationId(), feed);
            if (observations.findByRawObservationId(source.rawObservationId()).isPresent()
                    || rejections.existsById(source.rawObservationId())) {
                checkpoint.advanceTo(source.sequence());
                discoveredSequences.remove(source.rawObservationId());
                discoveredFeeds.remove(source.rawObservationId());
            } else if (!ids.contains(source.rawObservationId())) {
                ids.add(source.rawObservationId());
            }
        }
        return List.copyOf(ids);
    }

    @Override
    public ArrivalSnapshot snapshot(UUID id) {
        FeedIdentity feed = discoveredFeeds.getOrDefault(id, retryFeed(id).orElseGet(this::selectedFeed));
        Snapshot source = feed.demoRunId() == null
                ? ingestion.get().uri(feed.snapshotPath(), id).retrieve().body(Snapshot.class)
                : ingestion.get().uri(feed.snapshotPath(), feed.demoRunId(), id).retrieve().body(Snapshot.class);
        if (source == null) throw new IllegalStateException("Ingestion snapshot returned no body");
        discoveredSequences.put(id, source.sequence());
        discoveredFeeds.put(id, feed);
        if (!"NORMALIZED".equals(source.normalizationState()))
            throw new com.csd.occ.bunching.domain.PendingArrivalException();
        Optional<UUID> reference = source.arrivals().stream().map(Arrival::referenceVersionId).distinct().reduce(
                (first, second) -> { throw new com.csd.occ.bunching.domain.InvalidArrivalException("MIXED_REFERENCE_VERSIONS"); });
        return new ArrivalSnapshot(source.rawObservationId(), source.pollRunId(), reference, source.fetchedAt(),
                source.arrivals().stream().map(arrival -> new ArrivalSnapshot.Arrival(arrival.observationId(),
                        arrival.serviceId(), arrival.stopCode(), Optional.ofNullable(arrival.directionId()),
                        arrival.visitOrder(), Optional.ofNullable(arrival.estimatedArrivalAt()))).toList(),
                feed.classification(), feed.demoRunId());
    }

    @Override
    @Transactional
    public void reject(UUID id, Instant rejectedAt, String reason) {
        FeedIdentity feed = feedFor(id);
        if (!rejections.existsById(id)) {
            rejections.save(new SourceRejection(id, rejectedAt, reason, feed.classification(), feed.feedScope(), feed.demoRunId()));
            audit.save(new SourceProcessingAudit(id, rejectedAt, "SOURCE_REJECTED", reason, null,
                    feed.classification(), feed.feedScope(), feed.demoRunId()));
        }
        retries.deleteById(id);
        acknowledge(id);
    }

    @Override
    @Transactional
    public void defer(UUID id, Instant failedAt, Instant nextAttemptAt, RetryReason reason) {
        FeedIdentity feed = feedFor(id);
        retries.findById(id).ifPresentOrElse(retry -> retry.deferUntil(nextAttemptAt),
                () -> retries.save(new SourceRetry(id, nextAttemptAt, feed.feedScope(), feed.demoRunId())));
        audit.save(new SourceProcessingAudit(id, failedAt, "SOURCE_DEFERRED", reason.name(), nextAttemptAt,
                feed.classification(), feed.feedScope(), feed.demoRunId()));
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
        FeedIdentity feed = feedFor(id);
        Long sequence = discoveredSequences.remove(id);
        discoveredFeeds.remove(id);
        if (sequence == null) return;
        IngestionFeedCursor checkpoint = cursor.findById(1).orElseThrow();
        if (checkpoint.matches(feed.feedScope(), feed.demoRunId())) checkpoint.advanceTo(sequence);
    }

    private FeedIdentity feedFor(UUID id) {
        return discoveredFeeds.getOrDefault(id, retryFeed(id).orElseGet(this::selectedFeed));
    }

    private Optional<FeedIdentity> retryFeed(UUID id) {
        return retries.findById(id).map(HttpStoredArrivalAdapter::identity);
    }

    private static FeedIdentity identity(SourceRetry retry) {
        boolean demo = retry.demoRunId() != null;
        return new FeedIdentity(demo ? "SIMULATION" : "OBSERVATION_ONLY", retry.feedScope(), retry.demoRunId());
    }

    private FeedIdentity selectedFeed() {
        if (selection == null) return FeedIdentity.live();
        ConsumerSelection current = selection.current();
        return "DEMO".equals(current.mode()) ? FeedIdentity.demo(current.demoRunId()) : FeedIdentity.live();
    }

    private void align(IngestionFeedCursor checkpoint, FeedIdentity feed) {
        if (checkpoint.matches(feed.feedScope(), feed.demoRunId())) return;
        if (feed.demoRunId() != null) checkpoint.selectDemo(feed.demoRunId());
        else if (liveHead != null) checkpoint.selectLive(liveHead.currentLiveSequence());
        else checkpoint.selectLive(checkpoint.lastSequence());
    }

    private record FeedIdentity(String classification, String feedScope, UUID demoRunId) {
        private static FeedIdentity live() { return new FeedIdentity("OBSERVATION_ONLY", "LIVE", null); }
        private static FeedIdentity demo(UUID id) { return new FeedIdentity("SIMULATION", "DEMO:" + id, id); }
        private String pagePath() {
            return demoRunId == null ? "/internal/v1/arrival-snapshots" : "/internal/v1/demo-runs/" + demoRunId + "/arrival-snapshots";
        }
        private String snapshotPath() {
            return demoRunId == null ? "/internal/v1/arrival-snapshots/{id}" : "/internal/v1/demo-runs/{runId}/arrival-snapshots/{id}";
        }
    }

    private record FeedPage(List<Snapshot> items, long nextSequence) {
        private FeedPage { items = List.copyOf(items); }
    }
    private record Snapshot(long sequence, UUID rawObservationId, UUID pollRunId, String sourceKey, Instant fetchedAt,
                            String normalizationState, List<Arrival> arrivals) {
        private Snapshot { arrivals = List.copyOf(arrivals); }
    }
    private record Arrival(UUID observationId, UUID referenceVersionId, String stopCode, String serviceId,
                           Integer directionId, int visitOrder, Instant estimatedArrivalAt) {}
}
