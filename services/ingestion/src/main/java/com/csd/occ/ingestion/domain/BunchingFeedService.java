package com.csd.occ.ingestion.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
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
    private final PollRunRepository pollRuns;
    private final BusRouteStopRepository routeStops;

    public BunchingFeedService(RawObservationRepository rawObservations, BusArrivalObservationRepository arrivals,
            PollRunRepository pollRuns, BusRouteStopRepository routeStops) {
        this.rawObservations = rawObservations;
        this.arrivals = arrivals;
        this.pollRuns = pollRuns;
        this.routeStops = routeStops;
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
        Map<UUID, PollRun> runsById = pollRuns.findAllById(sources.stream()
                .map(RawObservation::getPollRunId).distinct().toList()).stream()
                .collect(Collectors.toMap(PollRun::getId, run -> run));
        Map<RouteKey, List<BusRouteStop>> stopsByRoute = new HashMap<>();
        return sources.stream().map(source -> new Snapshot(source.getFeedSequence(), source.getId(), source.getPollRunId(),
                Optional.ofNullable(runsById.get(source.getPollRunId()))
                        .map(PollRun::getRoutePositionPollCycleId).orElse(null),
                source.getSourceKey(), source.getFetchedAt(), "NORMALIZED",
                arrivalsBySource.getOrDefault(source.getId(), List.of()).stream()
                        .map(value -> arrival(value, stopsByRoute)).toList())).toList();
    }

    private Arrival arrival(BusArrivalObservation value, Map<RouteKey, List<BusRouteStop>> stopsByRoute) {
        RouteKey route = new RouteKey(value.getReferenceVersionId(), value.getServiceId());
        List<BusRouteStop> stops = stopsByRoute.computeIfAbsent(route,
                key -> routeStops.findByIdReferenceVersionIdAndIdServiceIdOrderByIdOperatorAscIdDirectionIdAscIdStopSequenceAsc(
                        key.referenceVersionId(), key.serviceId()));
        return new Arrival(value.getId(), value.getReferenceVersionId(), value.getStopCode(), value.getServiceId(),
                value.getOperator(), value.getDirectionId(), value.getVisitOrder(), value.getVisitNumber(),
                value.getEstimatedArrivalAt(), value.getOriginCode(), value.getDestinationCode(), value.getLatitude(),
                value.getLongitude(), uniqueStopSequence(value, stops).orElse(null));
    }

    private Optional<Integer> uniqueStopSequence(BusArrivalObservation arrival, List<BusRouteStop> stops) {
        if (arrival.getOperator() == null || arrival.getDirectionId() == null) return Optional.empty();
        List<Integer> matches = stops.stream()
                .filter(stop -> stop.id().operator().equals(arrival.getOperator())
                        && stop.id().directionId() == arrival.getDirectionId()
                        && stop.stopCode().equals(arrival.getStopCode()))
                .map(stop -> stop.id().stopSequence())
                .toList();
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    public record FeedPage(List<Snapshot> items, long nextSequence) {
        public FeedPage { items = List.copyOf(items); }
    }

    public record Snapshot(long sequence, UUID rawObservationId, UUID pollRunId, UUID sourcePollCycleId,
                           String sourceKey, Instant fetchedAt, String normalizationState, List<Arrival> arrivals) {
        public Snapshot { arrivals = List.copyOf(arrivals); }
    }

    public record Arrival(UUID observationId, UUID referenceVersionId, String stopCode, String serviceId,
                          String operator, Integer directionId, Integer visitOrder, Integer visitNumber,
                          Instant estimatedArrivalAt, String originCode, String destinationCode, BigDecimal latitude,
                          BigDecimal longitude, Integer routeStopSequence) {
    }

    private record RouteKey(UUID referenceVersionId, String serviceId) { }
}
