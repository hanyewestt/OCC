package com.csd.occ.disruption.infrastructure.scheduling;

import com.csd.occ.disruption.hazard.domain.HazardPollPort;
import com.csd.occ.disruption.hazard.domain.HazardSyncService;
import com.csd.occ.disruption.hazard.domain.HazardReassessmentService;
import com.csd.occ.disruption.domain.audit.ApplicationAuditFailureRecorder;
import com.csd.occ.disruption.infrastructure.config.HazardProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class HazardPollSchedule {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(HazardPollSchedule.class);
    private final HazardPollPort feed;
    private final HazardSyncService synchronization;
    private final HazardReassessmentService reassessment;
    private final HazardProperties settings;
    private final ApplicationAuditFailureRecorder auditFailures;
    public HazardPollSchedule(HazardPollPort feed, HazardSyncService synchronization,
            HazardReassessmentService reassessment, HazardProperties settings, ApplicationAuditFailureRecorder auditFailures) {
        this.feed = feed; this.synchronization = synchronization; this.reassessment = reassessment;
        this.settings = settings; this.auditFailures = auditFailures;
    }
    @Scheduled(fixedDelayString = "${occ.disruption.hazards.poll-interval}")
    public void consume() {
        if (settings.enabled()) for (String kind : java.util.List.of("FLOOD", "RAIL_DISRUPTION", "ROADWORKS")) {
            java.util.UUID pollId = null;
            try {
                var cursor = synchronization.cursor(kind);
                for (var poll : feed.page(kind, cursor.completedAt(), cursor.pollRunId()).polls()) {
                    pollId = poll.pollRunId();
                    synchronization.process(kind, poll, settings.staleAfter(), settings.absenceLimit(),
                            settings.absencePeriod(), settings.stateRetention());
                }
            } catch (RuntimeException failed) {
                try { auditFailures.processingFailure("HAZARD_POLL", kind, pollId); }
                catch (RuntimeException auditFailure) { log.warn("Could not record a bounded hazard processing failure event"); }
                log.error("Hazard {} synchronization failed; next interval will retry", kind);
            }
        }
        try { reassessment.enqueueExposedBuses(); }
        catch (RuntimeException failed) {
            try { auditFailures.processingFailure("HAZARD_REASSESSMENT", "BUNCHING", null); }
            catch (RuntimeException auditFailure) { log.warn("Could not record a bounded hazard processing failure event"); }
            log.error("Hazard reassessment failed; next interval will retry");
        }
    }
}
