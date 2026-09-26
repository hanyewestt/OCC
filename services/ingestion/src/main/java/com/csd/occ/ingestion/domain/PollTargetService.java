package com.csd.occ.ingestion.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
                .orElseThrow(ReferenceTopologyUnavailableException::new);
        log.info("Seeding poll targets for configured services...");
        int targetCount = 0;
        for (String serviceId : configuration.monitoredServices()) {
            Set<String> stops = Set.copyOf(topology.stopsForServices(version.id(), List.of(serviceId)));
            for (String stopCode : stops) {
                String scopeKey = "service:" + serviceId + ":stop:" + stopCode;
                PollTargetId id = new PollTargetId("DATAMALL", scopeKey);
                if (targets.existsById(id)) continue;
                PollTarget target = new PollTarget();
                target.setSource("DATAMALL");
                target.setScopeKey(scopeKey);
                target.setStopCode(stopCode);
                target.setServiceId(serviceId);
                target.setEnabled(true);
                target.setPollIntervalSeconds(60);
                target.setCreatedAt(Instant.now(clock));
                target.setUpdatedAt(Instant.now(clock));
                targets.save(target);
                targetCount++;
            }
        }
        log.info("Seeding complete. Saved {} targets.", targetCount);
    }
}
