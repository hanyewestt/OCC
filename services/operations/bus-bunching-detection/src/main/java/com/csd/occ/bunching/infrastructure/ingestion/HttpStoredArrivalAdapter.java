package com.csd.occ.bunching.infrastructure.ingestion;

import com.csd.occ.bunching.domain.ArrivalSnapshot;
import com.csd.occ.bunching.domain.ConsumerState;
import com.csd.occ.bunching.domain.ConsumerStateRepository;
import com.csd.occ.bunching.domain.ConsumerSelectionService;
import com.csd.occ.bunching.domain.IngestionFeedHeadPort;
import com.csd.occ.bunching.domain.SourceProcessingState;
import com.csd.occ.bunching.domain.SourceProcessingStateRepository;
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
    private final ConsumerStateRepository consumers;
    private final SourceProcessingStateRepository states;
    private final ConsumerSelectionService selection;
    private final IngestionFeedHeadPort liveHead;
    private final Map<UUID, Long> discoveredSequences = new ConcurrentHashMap<>();
    private final Map<UUID, FeedIdentity> discoveredFeeds = new ConcurrentHashMap<>();

    @Autowired
    public HttpStoredArrivalAdapter(RestClient ingestionRestClient, ConsumerStateRepository consumers,
                                    SourceProcessingStateRepository states,
                                    ConsumerSelectionService selection, IngestionFeedHeadPort liveHead) {
        this.ingestion = ingestionRestClient; this.consumers = consumers; this.states = states;
        this.selection = selection; this.liveHead = liveHead;
    }

    @Override
    @Transactional
    public List<UUID> pendingIds(int limit, Instant now) {
        if (limit < 1 || limit > 1_000) throw new IllegalArgumentException("Limit must be 1..1000");
        FeedIdentity feed = selectedFeed();
        ConsumerState checkpoint = consumers.findWithLockById(1).orElseThrow();
        align(checkpoint, feed);

        List<UUID> ids = new ArrayList<>();
        retryStates(feed, now, limit).forEach(retry -> {
            discoveredFeeds.put(retry.rawObservationId(), identity(retry));
            if (!retry.isProcessed() && !retry.isRejected()) ids.add(retry.rawObservationId());
        });
        if (ids.size() == limit) return List.copyOf(ids);

        FeedPage page = ingestion.get().uri(builder -> builder.path(feed.pagePath())
                .queryParam("afterSequence", checkpoint.lastSequence()).queryParam("size", limit - ids.size()).build())
                .retrieve().body(FeedPage.class);
        if (page == null) throw new IllegalStateException("Ingestion feed returned no body");
        for (Snapshot source : page.items()) {
            discoveredSequences.put(source.rawObservationId(), source.sequence());
            discoveredFeeds.put(source.rawObservationId(), feed);
            if (states.existsById(source.rawObservationId())) {
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
        states.findById(id).ifPresentOrElse(state -> state.reject(rejectedAt, reason),
                () -> { SourceProcessingState state = new SourceProcessingState(id, rejectedAt, feed.demoRunId()); state.reject(rejectedAt, reason); states.save(state); });
        acknowledge(id);
    }

    @Override
    @Transactional
    public void defer(UUID id, Instant failedAt, Instant nextAttemptAt, RetryReason reason) {
        FeedIdentity feed = feedFor(id);
        states.findById(id).ifPresentOrElse(state -> state.deferUntil(nextAttemptAt),
                () -> states.save(new SourceProcessingState(id, nextAttemptAt, feed.demoRunId())));
        acknowledge(id);
    }

    @Override
    @Transactional
    public void complete(UUID id) {
        acknowledge(id);
    }

    @Override
    @Transactional
    public void acknowledge(UUID id) {
        FeedIdentity feed = feedFor(id);
        Long sequence = discoveredSequences.remove(id);
        discoveredFeeds.remove(id);
        if (sequence == null) return;
        ConsumerState checkpoint = consumers.findWithLockById(1).orElseThrow();
        if (checkpoint.matches(feed.demoRunId() == null ? "LIVE" : "DEMO", feed.demoRunId())) checkpoint.advanceTo(sequence);
    }

    private FeedIdentity feedFor(UUID id) {
        return discoveredFeeds.getOrDefault(id, retryFeed(id).orElseGet(this::selectedFeed));
    }

    private Optional<FeedIdentity> retryFeed(UUID id) {
        return states.findById(id).map(HttpStoredArrivalAdapter::identity);
    }

    private static FeedIdentity identity(SourceProcessingState retry) {
        boolean demo = retry.demoRunId() != null;
        return new FeedIdentity(demo ? "SIMULATION" : "OBSERVATION_ONLY", retry.demoRunId());
    }

    private FeedIdentity selectedFeed() {
        if (selection == null) return FeedIdentity.live();
        ConsumerState current = selection.current();
        return "DEMO".equals(current.mode()) ? FeedIdentity.demo(current.demoRunId()) : FeedIdentity.live();
    }

    private void align(ConsumerState checkpoint, FeedIdentity feed) {
        if (checkpoint.matches(feed.demoRunId() == null ? "LIVE" : "DEMO", feed.demoRunId())) return;
        throw new IllegalStateException("consumer state changed while selecting a feed");
    }

    private List<SourceProcessingState> retryStates(FeedIdentity feed, Instant now, int limit) {
        return feed.demoRunId() == null
                ? states.findByDemoRunIdIsNullAndStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc("DEFERRED", now, PageRequest.of(0, limit))
                : states.findByDemoRunIdAndStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(feed.demoRunId(), "DEFERRED", now, PageRequest.of(0, limit));
    }

    private record FeedIdentity(String classification, UUID demoRunId) {
        private static FeedIdentity live() { return new FeedIdentity("OBSERVATION_ONLY", null); }
        private static FeedIdentity demo(UUID id) { return new FeedIdentity("SIMULATION", id); }
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
