package com.csd.occ.ingestion.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RootCauseContextService {
    private final DemoRunRepository runs;
    private final BusArrivalObservationRepository arrivals;
    private final TrafficIncidentObservationRepository incidents;
    private final PollingConfigurationPort configuration;
    private final java.time.Clock clock;

    public RootCauseContextService(DemoRunRepository runs, BusArrivalObservationRepository arrivals,
            TrafficIncidentObservationRepository incidents, PollingConfigurationPort configuration, java.time.Clock clock) {
        this.runs = runs; this.arrivals = arrivals; this.incidents = incidents;
        this.configuration = configuration; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public RootCauseContextSnapshot find(UUID runId, Instant at, String serviceId, String stopCode,
                                         Integer directionId, List<UUID> observationIds) {
        if (at == null || serviceId == null || serviceId.isBlank() || stopCode == null || stopCode.isBlank())
            throw new IllegalArgumentException("at, serviceId and stopCode are required");
        if (observationIds == null || observationIds.size() < 2 || observationIds.size() > 10)
            throw new IllegalArgumentException("arrivalObservationIds must contain between 2 and 10 IDs");
        if (observationIds.stream().distinct().count() != observationIds.size())
            throw new IllegalArgumentException("arrivalObservationIds must be unique");
        DemoRun run = runs.findById(runId).orElseThrow(() -> new DemoRunNotFoundException(runId));
        if (run.expiredAt(clock.instant().minus(configuration.retention()))) throw new DemoRunNotFoundException(runId);
        if (!"SIMULATION".equals(run.classification()))
            throw new IllegalArgumentException("Only SIMULATION run context can be used for diagnosis");
        List<BusArrivalObservation> found = arrivals.findAllById(observationIds);
        if (found.size() != observationIds.size()) throw new IllegalArgumentException("One or more arrival observations do not exist");
        if (found.stream().anyMatch(arrival -> !"SIMULATION".equals(arrival.getClassification())
                || !runId.equals(arrival.getDemoRunId())))
            throw new IllegalArgumentException("Arrival observations must belong to the requested simulation run");
        if (found.stream().anyMatch(arrival -> !serviceId.equals(arrival.getServiceId())
                || !stopCode.equals(arrival.getStopCode()) || !Objects.equals(directionId, arrival.getDirectionId())
                || arrival.getFetchedAt() == null || arrival.getFetchedAt().isAfter(at)))
            throw new IllegalArgumentException("Arrival observations must match the confirmed event and its time window");

        List<String> missing = new ArrayList<>();
        List<RootCauseContextSnapshot.RootCauseArrival> arrivalEvidence = found.stream().map(arrival -> {
            String evidenceId = "bus-arrival:" + arrival.getId();
            if (arrival.getLatitude() == null || arrival.getLongitude() == null) missing.add("arrival-location:" + arrival.getId());
            return new RootCauseContextSnapshot.RootCauseArrival(evidenceId, arrival.getId(), arrival.getRawObservationId(),
                    arrival.getPollRunId(), arrival.getServiceId(), arrival.getStopCode(), arrival.getDirectionId(),
                    arrival.getEstimatedArrivalAt(), arrival.getFetchedAt(), arrival.getLatitude(), arrival.getLongitude(),
                    arrival.getLoadCategory(), arrival.getVehicleType(),
                    arrival.getClassification(), arrival.getDemoRunId(), arrival.getReferenceVersionId());
        }).toList();

        List<RootCauseContextSnapshot.RootCauseIncident> incidentEvidence = incidents
                .findByDemoRunIdOrderByFetchedAtDesc(runId).stream()
                .filter(incident -> "SIMULATION".equals(incident.classification()))
                .filter(incident -> incident.observedAt() == null || !incident.observedAt().isAfter(at))
                .filter(incident -> !incident.fetchedAt().isAfter(at))
                .limit(50)
                .map(incident -> {
                    if (!runId.equals(incident.demoRunId()))
                        throw new IllegalArgumentException("Traffic incidents must belong to the requested simulation run");
                    if (incident.latitude() == null || incident.longitude() == null)
                        missing.add("incident-location:" + incident.id());
                    return new RootCauseContextSnapshot.RootCauseIncident("traffic-incident:" + incident.id(),
                            incident.id(), incident.incidentType(), incident.message(), incident.latitude(), incident.longitude(),
                            incident.observedAt(), incident.fetchedAt(), incident.classification(), incident.demoRunId());
                })
                .toList();
        List<UUID> referenceVersions = arrivalEvidence.stream()
                .map(RootCauseContextSnapshot.RootCauseArrival::referenceVersionId)
                .filter(Objects::nonNull).distinct().toList();
        UUID reference = referenceVersions.size() == 1 ? referenceVersions.getFirst() : null;
        if (reference == null) missing.add("reference-version");
        if (referenceVersions.size() > 1) missing.add("mixed-reference-versions");
        if (incidentEvidence.isEmpty()) missing.add("traffic-incidents");
        return new RootCauseContextSnapshot("SIMULATION", run.id(), reference, arrivalEvidence, incidentEvidence, missing);
    }
}
