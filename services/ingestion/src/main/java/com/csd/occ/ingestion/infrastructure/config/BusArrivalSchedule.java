package com.csd.occ.ingestion.infrastructure.config;

import com.csd.occ.ingestion.domain.BusArrivalService;
import com.csd.occ.ingestion.domain.PollTargetService;
import com.csd.occ.ingestion.domain.ReferenceImportService;
import com.csd.occ.ingestion.domain.ReferenceTopologyPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class BusArrivalSchedule {
    private static final Logger log = LoggerFactory.getLogger(BusArrivalSchedule.class);
    private final BusArrivalService arrivals;
    private final ReferenceTopologyPort topology;
    private final ReferenceImportService imports;
    private final PollTargetService targets;
    private final IngestionScheduleProperties scheduleProperties;

    public BusArrivalSchedule(BusArrivalService arrivals, ReferenceTopologyPort topology,
            ReferenceImportService imports, PollTargetService targets,
            IngestionScheduleProperties scheduleProperties) {
        this.arrivals = arrivals;
        this.topology = topology;
        this.imports = imports;
        this.targets = targets;
        this.scheduleProperties = scheduleProperties;
    }

    @Scheduled(fixedRateString = "${occ.ingestion.schedule.bus-arrival-poll-interval}",
            initialDelayString = "${occ.ingestion.schedule.bus-arrival-initial-delay}")
    public void poll() {
        if (!scheduleProperties.busArrivalEnabled()) {
            return;
        }
        if (topology.activeVersion().isEmpty() && !restoreReferenceTopology()) return;
        arrivals.poll();
    }

    private boolean restoreReferenceTopology() {
        try {
            log.warn("No active bus reference topology is available; attempting a DataMall reference import.");
            imports.refresh();
            targets.seed();
            return true;
        } catch (RuntimeException exception) {
            log.error("Skipping bus-arrival polling because the reference topology import failed.", exception);
            return false;
        }
    }
}
