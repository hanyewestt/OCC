package com.csd.occ.disruption.infrastructure.scheduling;

import com.csd.occ.disruption.infrastructure.config.TrafficIncidentProperties;
import com.csd.occ.disruption.traffic.domain.TrafficIncidentPollPort;
import com.csd.occ.disruption.traffic.domain.TrafficIncidentSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "occ.disruption.traffic", name = "poll-enabled", havingValue = "true")
public class TrafficIncidentPollSchedule {
    private static final Logger log = LoggerFactory.getLogger(TrafficIncidentPollSchedule.class);
    private final TrafficIncidentPollPort feed;
    private final TrafficIncidentSyncService synchronization;
    private final TrafficIncidentProperties properties;

    public TrafficIncidentPollSchedule(TrafficIncidentPollPort feed,
            TrafficIncidentSyncService synchronization, TrafficIncidentProperties properties) {
        this.feed = feed; this.synchronization = synchronization; this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${occ.disruption.traffic.poll-interval}")
    public void consumeCompletedPolls() {
        try {
            var cursor = synchronization.cursor();
            TrafficIncidentPollPort.TrafficIncidentPollPage page = feed.page(
                    cursor.completedAt(), cursor.pollRunId(), properties.pageSize());
            for (TrafficIncidentPollPort.TrafficIncidentPoll poll : page.polls())
                synchronization.process(poll, properties.staleAfter(), properties.clearAfterSuccessfulAbsences(), properties.minimumAbsencePeriod());
            if (page.hasMore()) log.info("Traffic poll feed has more pages; remaining rows will be processed on the next interval.");
        } catch (RuntimeException failure) {
            // Do not log provider or SQL exception text; retry on the next fixed-delay interval.
            log.error("Traffic incident poll synchronization failed; it will retry.");
        }
    }
}
