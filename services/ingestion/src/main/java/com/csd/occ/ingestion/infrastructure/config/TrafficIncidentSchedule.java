package com.csd.occ.ingestion.infrastructure.config;

import com.csd.occ.ingestion.domain.TrafficIncidentPollingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TrafficIncidentSchedule {
    private static final Logger log = LoggerFactory.getLogger(TrafficIncidentSchedule.class);
    private final TrafficIncidentPollingService polling;
    private final IngestionScheduleProperties properties;

    public TrafficIncidentSchedule(TrafficIncidentPollingService polling, IngestionScheduleProperties properties) {
        this.polling = polling;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${occ.ingestion.schedule.traffic-incident-poll-interval}",
            initialDelayString = "${occ.ingestion.schedule.traffic-incident-initial-delay}")
    public void poll() {
        if (!properties.trafficIncidentEnabled()) return;
        try {
            polling.pollOnce();
        } catch (RuntimeException failure) {
            log.error("Traffic incident ingestion failed; the next scheduled poll will retry.");
        }
    }
}
