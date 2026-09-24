package com.csd.occ.ingestion.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PollTargetService {
    private static final Logger log = LoggerFactory.getLogger(PollTargetService.class);
    private final PollTargetRepository targets;
    private final ReferenceTopologyPort topology;
    private final PollingConfigurationPort configuration;
    private final Clock clock;

    public PollTargetService(PollTargetRepository targets, ReferenceTopologyPort topology,
            PollingConfigurationPort configuration, Clock clock) {
        this.targets = targets;
        this.topology = topology;
        this.configuration = configuration;
        this.clock = clock;
    }

    public void seed() {
        ReferenceTopologyPort.ReferenceTopologyVersion version = topology.activeVersion()
                .orElseThrow(() -> new IllegalStateException("No active bus reference topology is available"));
        log.info("Seeding poll targets for configured services...");
        Set<String> stops = Set.copyOf(topology.stopsForServices(version.id(), configuration.monitoredServices()));
        for (String stopCode : stops) {
            PollTargetId id = new PollTargetId("DATAMALL", "stop:" + stopCode);
            if (targets.existsById(id)) continue;
            PollTarget target = new PollTarget();
            target.setSource("DATAMALL");
            target.setScopeKey("stop:" + stopCode);
            target.setStopCode(stopCode);
            target.setEnabled(true);
            target.setPollIntervalSeconds(60);
            target.setCreatedAt(Instant.now(clock));
            target.setUpdatedAt(Instant.now(clock));
            targets.save(target);
        }
        log.info("Seeding complete. Saved {} targets.", stops.size());
    }
}
