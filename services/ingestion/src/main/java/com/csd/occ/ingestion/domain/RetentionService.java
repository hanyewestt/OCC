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
    private final HazardObservationRepository hazards;

    public RetentionService(BusArrivalObservationRepository arrivals, RawObservationRepository rawObservations,
            PollRunRepository runs, ReferenceDatasetVersionRepository versions, BusRouteStopRepository routeStops,
            BusServiceRepository services, BusStopRepository stops, PollingConfigurationPort configuration, Clock clock,
            DemoRunRepository demos, TrafficIncidentObservationRepository incidents,
            DemoVehicleAssignmentRepository assignments, BunchingControlPort bunching, PollTargetRepository targets,
            HazardObservationRepository hazards) {
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
        this.hazards = hazards;
    }

    @Transactional
    public long purgeExpiredObservations() {
        Instant cutoff = Instant.now(clock).minus(configuration.retention());
        versions.lockTopology();
        long deletedRows = arrivals.deleteExpiredLiveObservations(cutoff);
        deletedRows += incidents.deleteExpiredLive(cutoff);
        deletedRows += hazards.deleteExpiredLive(cutoff);
        deletedRows += rawObservations.deleteExpiredLiveObservations(cutoff);
        var protectedRuns = bunching.protectedDemoRuns();
        for (var run : demos.findExpired(cutoff)) {
            if (protectedRuns.contains(run.id())) continue;
            deletedRows += arrivals.deleteByDemoRunId(run.id());
            deletedRows += incidents.deleteByDemoRunId(run.id());
            deletedRows += hazards.deleteByDemoRunId(run.id());
            deletedRows += rawObservations.deleteByDemoRunId(run.id());
            deletedRows += assignments.deleteByIdDemoRunId(run.id());
            deletedRows += runs.deleteByDemoRunId(run.id());
            demos.delete(run);
            deletedRows++;
        }
        deletedRows += runs.deleteCompletedWithoutRawBefore(cutoff);
        deletedRows += targets.deleteDisabledLegacyTargets();
        for (var versionId : versions.findExpiredUnreferencedIds(cutoff)) {
            deletedRows += deleteTopology(versionId);
        }
        return deletedRows;
    }

    private long deleteTopology(java.util.UUID versionId) {
        long deletedRows = routeStops.deleteByIdReferenceVersionId(versionId);
        deletedRows += services.deleteByIdReferenceVersionId(versionId);
        deletedRows += stops.deleteByIdReferenceVersionId(versionId);
        versions.deleteById(versionId);
        return deletedRows + 1;
    }
}
