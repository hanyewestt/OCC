package com.csd.occ.disruption.infrastructure.scheduling;

import com.csd.occ.disruption.hazard.domain.HazardPollPort;
import com.csd.occ.disruption.hazard.domain.HazardSyncService;
import com.csd.occ.disruption.infrastructure.config.HazardProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class HazardPollSchedule {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(HazardPollSchedule.class);
    private final HazardPollPort feed;
    private final HazardSyncService synchronization;
    private final HazardProperties settings;
    public HazardPollSchedule(HazardPollPort feed, HazardSyncService synchronization, HazardProperties settings) {
        this.feed = feed; this.synchronization = synchronization; this.settings = settings;
    }
    @Scheduled(fixedDelayString = "${occ.disruption.hazards.poll-interval}")
    public void consume() {
        if (!settings.enabled()) return;
        for (String kind : java.util.List.of("FLOOD", "RAIL_DISRUPTION", "ROADWORKS")) {
            try {
                var cursor = synchronization.cursor(kind);
                for (var poll : feed.page(kind, cursor.completedAt(), cursor.pollRunId()).polls())
                    synchronization.process(kind, poll, settings.staleAfter(), settings.absenceLimit(), settings.absencePeriod());
            } catch (RuntimeException failed) { log.error("Hazard {} synchronization failed; next interval will retry", kind); }
        }
    }
}
