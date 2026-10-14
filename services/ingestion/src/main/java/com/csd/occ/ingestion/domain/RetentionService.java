package com.csd.occ.ingestion.domain;

import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RetentionService {
    private final BusArrivalObservationRepository arrivals;
    private final RawObservationRepository rawObservations;
    private final PollRunRepository runs;
    private final ReferenceDatasetVersionRepository versions;
    private final BusRouteStopRepository routeStops;
    private final BusServiceRepository services;
    private final BusStopRepository stops;
    private final PollingConfigurationPort configuration;
    private final Clock clock;
    private final DemoRunRepository demos;
    private final TrafficIncidentObservationRepository incidents;
    private final DemoVehicleAssignmentRepository assignments;
    private final BunchingControlPort bunching;
    private final PollTargetRepository targets;

    public RetentionService(BusArrivalObservationRepository arrivals, RawObservationRepository rawObservations,
            PollRunRepository runs, ReferenceDatasetVersionRepository versions, BusRouteStopRepository routeStops,
            BusServiceRepository services, BusStopRepository stops, PollingConfigurationPort configuration, Clock clock,
            DemoRunRepository demos, TrafficIncidentObservationRepository incidents,
            DemoVehicleAssignmentRepository assignments, BunchingControlPort bunching, PollTargetRepository targets) {
        this.arrivals = arrivals;
        this.rawObservations = rawObservations;
        this.runs = runs;
        this.versions = versions;
        this.routeStops = routeStops;
        this.services = services;
        this.stops = stops;
        this.configuration = configuration;
        this.clock = clock;
        this.demos = demos; this.incidents = incidents; this.assignments = assignments; this.bunching = bunching;
        this.targets = targets;
    }

    @Transactional
    public void purgeExpiredObservations() {
        Instant cutoff = Instant.now(clock).minus(configuration.retention());
        versions.lockTopology();
        arrivals.deleteExpiredLiveObservations(cutoff);
        incidents.deleteExpiredLive(cutoff);
        rawObservations.deleteExpiredLiveObservations(cutoff);
        var protectedRuns = bunching.protectedDemoRuns();
        demos.findExpired(cutoff).stream().filter(run -> !protectedRuns.contains(run.id())).forEach(run -> {
            arrivals.deleteByDemoRunId(run.id());
            incidents.deleteByDemoRunId(run.id());
            rawObservations.deleteByDemoRunId(run.id());
            assignments.deleteByIdDemoRunId(run.id());
            runs.deleteByDemoRunId(run.id());
            demos.delete(run);
        });
        runs.deleteCompletedWithoutRawBefore(cutoff);
        targets.deleteDisabledLegacyTargets();
        versions.findExpiredUnreferencedIds(cutoff).forEach(this::deleteTopology);
    }

    private void deleteTopology(java.util.UUID versionId) {
        routeStops.deleteByIdReferenceVersionId(versionId);
        services.deleteByIdReferenceVersionId(versionId);
        stops.deleteByIdReferenceVersionId(versionId);
        versions.deleteById(versionId);
    }
}
