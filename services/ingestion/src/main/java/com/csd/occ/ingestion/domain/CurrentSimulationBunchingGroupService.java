package com.csd.occ.ingestion.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves detector observations only when they belong to the newest complete replay snapshot. */
@Service
public class CurrentSimulationBunchingGroupService {
    private final DemoRunRepository runs;
    private final RawObservationRepository raw;
    private final BusArrivalObservationRepository arrivals;
    private final BunchingControlPort bunching;
    private final Clock clock;

    public CurrentSimulationBunchingGroupService(DemoRunRepository runs, RawObservationRepository raw,
            BusArrivalObservationRepository arrivals, BunchingControlPort bunching, Clock clock) {
        this.runs = runs; this.raw = raw; this.arrivals = arrivals; this.bunching = bunching; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<Group> find(UUID runId, UUID episodeId) {
        var run = runs.findById(runId).orElse(null);
        if (run == null || !List.of("RUNNING", "REPLAY_COMPLETE").contains(run.status())) return Optional.empty();
        var evidence = bunching.currentEvidence(runId, episodeId).orElse(null);
        var now = clock.instant();
        if (evidence == null || !runId.equals(evidence.demoRunId()) || !episodeId.equals(evidence.bunchingEventId())
                || !run.configuration().serviceId().equals(evidence.serviceId())
                || !run.configuration().monitoredStopCode().equals(evidence.stopCode())
                || run.configuration().directionId() != evidence.directionId() || !"BUNCHING".equals(evidence.state())
                || !evidence.fresh() || evidence.rawObservationId() == null || evidence.fetchedAt() == null
                || evidence.validUntil() == null || evidence.fetchedAt().isAfter(now) || !now.isBefore(evidence.validUntil())
                || evidence.affectedObservationIds() == null || evidence.affectedObservationIds().size() < 2
                || evidence.affectedObservationIds().size() > 100
                || evidence.affectedObservationIds().stream().anyMatch(java.util.Objects::isNull)
                || evidence.affectedObservationIds().stream().distinct().count() != evidence.affectedObservationIds().size())
            return Optional.empty();
        var latest = raw.findLatestDemoBunchingSnapshot(runId, "demo:" + runId + ":stop:" + evidence.stopCode(), PageRequest.of(0, 1));
        if (latest.size() != 1 || !evidence.rawObservationId().equals(latest.getFirst().getId())
                || !evidence.fetchedAt().equals(latest.getFirst().getFetchedAt())) return Optional.empty();
        var found = arrivals.findAllById(evidence.affectedObservationIds());
        if (found.size() != evidence.affectedObservationIds().size() || found.stream().anyMatch(value ->
                !"SIMULATION".equals(value.getClassification()) || !runId.equals(value.getDemoRunId())
                || !evidence.rawObservationId().equals(value.getRawObservationId())
                || !evidence.serviceId().equals(value.getServiceId()) || !evidence.stopCode().equals(value.getStopCode())
                || !java.util.Objects.equals(evidence.directionId(), value.getDirectionId())
                || value.getEstimatedArrivalAt() == null || value.getEstimatedArrivalAt().isBefore(now)
                || value.getVisitOrder() == null)) return Optional.empty();
        if (found.stream().map(BusArrivalObservation::getVisitOrder).distinct().count() != found.size()) return Optional.empty();
        var ordered = found.stream().sorted(Comparator.comparing(BusArrivalObservation::getEstimatedArrivalAt)
                .thenComparing(BusArrivalObservation::getVisitOrder)).toList();
        var resolved = ordered.stream().map(value -> arrivals.findSimulationArrivalDetails(runId, value.getId())).toList();
        if (resolved.stream().anyMatch(Optional::isEmpty)) return Optional.empty();
        var references = resolved.stream().map(Optional::orElseThrow).map(value -> value.vehicle().busId()).toList();
        if (references.stream().anyMatch(value -> value == null || value.isBlank())
                || references.stream().distinct().count() != references.size()) return Optional.empty();
        var gaps = java.util.stream.IntStream.range(1, ordered.size())
                .mapToLong(index -> Duration.between(ordered.get(index - 1).getEstimatedArrivalAt(),
                        ordered.get(index).getEstimatedArrivalAt()).getSeconds()).boxed().toList();
        return Optional.of(new Group(runId, episodeId, evidence.serviceId(), evidence.stopCode(), evidence.directionId(),
                evidence.rawObservationId(), evidence.fetchedAt(), evidence.validUntil(), references, gaps));
    }

    public record Group(UUID demoRunId, UUID bunchingEventId, String serviceId, String stopCode, int directionId,
                        UUID rawObservationId, Instant fetchedAt, Instant validUntil, List<String> affectedBusReferences,
                        List<Long> adjacentGapSeconds) {
        public Group { affectedBusReferences = List.copyOf(affectedBusReferences); adjacentGapSeconds = List.copyOf(adjacentGapSeconds); }
    }
}
