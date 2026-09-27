package com.csd.occ.ingestion.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BunchingFeedService {
    private static final int MAX_PAGE_SIZE = 1_000;
    private final RawObservationRepository rawObservations;
    private final BusArrivalObservationRepository arrivals;

    public BunchingFeedService(RawObservationRepository rawObservations, BusArrivalObservationRepository arrivals) {
        this.rawObservations = rawObservations;
        this.arrivals = arrivals;
    }

    @Transactional(readOnly = true)
    public FeedPage page(long afterSequence, int size) {
        if (afterSequence < 0) throw new IllegalArgumentException("afterSequence must not be negative");
        if (size < 1 || size > MAX_PAGE_SIZE) throw new IllegalArgumentException("size must be 1..1000");
        List<RawObservation> sources = rawObservations.findBunchingFeedAfter(afterSequence, PageRequest.of(0, size))
                .getContent();
        return new FeedPage(snapshots(sources), sources.isEmpty() ? afterSequence : sources.getLast().getFeedSequence());
    }

    @Transactional(readOnly = true)
    public Optional<Snapshot> snapshot(UUID rawObservationId) {
        return rawObservations.findBunchingFeedById(rawObservationId).map(source -> snapshots(List.of(source)).getFirst());
    }

    @Transactional(readOnly = true)
    public long liveHeadSequence() {
        return rawObservations.liveBunchingFeedHead();
    }
    @Transactional(readOnly = true)
    public FeedPage demoPage(UUID demoRunId, long afterSequence, int size) {
        List<RawObservation> sources = rawObservations.findDemoBunchingFeedAfter(demoRunId, afterSequence, PageRequest.of(0, size)).getContent();
        return new FeedPage(snapshots(sources), sources.isEmpty() ? afterSequence : sources.getLast().getFeedSequence());
    }
    @Transactional(readOnly = true)
    public Optional<Snapshot> demoSnapshot(UUID demoRunId, UUID rawObservationId) {
        return rawObservations.findDemoBunchingFeedById(demoRunId, rawObservationId).map(source -> snapshots(List.of(source)).getFirst());
    }

    private List<Snapshot> snapshots(List<RawObservation> sources) {
        Map<UUID, List<BusArrivalObservation>> arrivalsBySource = arrivals.findByRawObservationIdIn(
                sources.stream().map(RawObservation::getId).toList()).stream()
                .collect(Collectors.groupingBy(BusArrivalObservation::getRawObservationId));
        return sources.stream().map(source -> new Snapshot(source.getFeedSequence(), source.getId(), source.getPollRunId(),
                source.getSourceKey(), source.getFetchedAt(), "NORMALIZED",
                arrivalsBySource.getOrDefault(source.getId(), List.of()).stream().map(this::arrival).toList())).toList();
    }

    private Arrival arrival(BusArrivalObservation value) {
        return new Arrival(value.getId(), value.getReferenceVersionId(), value.getStopCode(), value.getServiceId(),
                value.getDirectionId(), value.getVisitOrder(), value.getEstimatedArrivalAt(), value.getOriginCode(),
                value.getDestinationCode(), value.getLatitude(), value.getLongitude());
    }

    public record FeedPage(List<Snapshot> items, long nextSequence) {
        public FeedPage { items = List.copyOf(items); }
    }

    public record Snapshot(long sequence, UUID rawObservationId, UUID pollRunId, String sourceKey, Instant fetchedAt,
                           String normalizationState, List<Arrival> arrivals) {
        public Snapshot { arrivals = List.copyOf(arrivals); }
    }

    public record Arrival(UUID observationId, UUID referenceVersionId, String stopCode, String serviceId,
                          Integer directionId, Integer visitOrder, Instant estimatedArrivalAt, String originCode,
                          String destinationCode, BigDecimal latitude, BigDecimal longitude) {
    }
}
