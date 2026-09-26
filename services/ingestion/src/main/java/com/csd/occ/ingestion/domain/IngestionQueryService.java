package com.csd.occ.ingestion.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class IngestionQueryService {
    private final BusArrivalObservationRepository arrivals;
    private final FeedStatusRepository feedStatuses;
    private final PollRunRepository runs;
    private final ReferenceTopologyPort topology;
    private final Clock clock;

    public IngestionQueryService(BusArrivalObservationRepository arrivals, FeedStatusRepository feedStatuses,
            PollRunRepository runs, ReferenceTopologyPort topology, Clock clock) {
        this.arrivals = arrivals;
        this.feedStatuses = feedStatuses;
        this.runs = runs;
        this.topology = topology;
        this.clock = clock;
    }

    public Page<CurrentBusArrivalView> currentArrivals(String stopCode, String serviceId, Pageable pageable) {
        return arrivals.findCurrent(blankToNull(stopCode), blankToNull(serviceId), pageable);
    }

    public Page<BusArrivalObservation> arrivalHistory(String stopCode, String serviceId, Instant from, Instant to,
            Pageable pageable) {
        return arrivals.findHistory(blankToNull(stopCode), blankToNull(serviceId), from, to, pageable);
    }

    public Page<PollRun> pollRuns(Pageable pageable) {
        return runs.findByClassification("OBSERVATION_ONLY", pageable);
    }

    public List<FeedStatus> feedStatuses() {
        return feedStatuses.findAll();
    }

    public Optional<ReferenceTopologyPort.ReferenceTopologyVersion> activeReferenceVersion() {
        return topology.activeVersion();
    }

    public Instant now() {
        return Instant.now(clock);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
