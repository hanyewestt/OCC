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

    public RetentionService(BusArrivalObservationRepository arrivals, RawObservationRepository rawObservations,
            PollRunRepository runs, ReferenceDatasetVersionRepository versions, BusRouteStopRepository routeStops,
            BusServiceRepository services, BusStopRepository stops, PollingConfigurationPort configuration, Clock clock) {
        this.arrivals = arrivals;
        this.rawObservations = rawObservations;
        this.runs = runs;
        this.versions = versions;
        this.routeStops = routeStops;
        this.services = services;
        this.stops = stops;
        this.configuration = configuration;
        this.clock = clock;
    }

    @Transactional
    public void purgeExpiredObservations() {
        Instant cutoff = Instant.now(clock).minus(configuration.retention());
        arrivals.deleteExpiredLiveObservations(cutoff);
        rawObservations.deleteExpiredLiveObservations(cutoff);
        runs.deleteCompletedWithoutRawBefore(cutoff);
        versions.findExpiredUnreferencedIds(cutoff).forEach(this::deleteTopology);
    }

    private void deleteTopology(java.util.UUID versionId) {
        routeStops.deleteByIdReferenceVersionId(versionId);
        services.deleteByIdReferenceVersionId(versionId);
        stops.deleteByIdReferenceVersionId(versionId);
        versions.deleteById(versionId);
    }
}
