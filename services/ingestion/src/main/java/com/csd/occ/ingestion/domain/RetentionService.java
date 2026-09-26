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
    private final PollingConfigurationPort configuration;
    private final Clock clock;

    public RetentionService(BusArrivalObservationRepository arrivals, RawObservationRepository rawObservations,
            PollRunRepository runs, PollingConfigurationPort configuration, Clock clock) {
        this.arrivals = arrivals;
        this.rawObservations = rawObservations;
        this.runs = runs;
        this.configuration = configuration;
        this.clock = clock;
    }

    @Transactional
    public void purgeExpiredObservations() {
        Instant cutoff = Instant.now(clock).minus(configuration.retention());
        arrivals.deleteExpiredLiveObservations(cutoff);
        rawObservations.deleteExpiredLiveObservations(cutoff);
        runs.deleteCompletedWithoutRawBefore(cutoff);
    }
}
