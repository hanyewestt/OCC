package com.csd.occ.disruption.hazard.domain;

import com.csd.occ.disruption.bunching.domain.ConsumerSelectionService;
import com.csd.occ.disruption.bunching.domain.DiagnosisOutbox;
import com.csd.occ.disruption.bunching.domain.DiagnosisOutboxRepository;
import com.csd.occ.disruption.bunching.domain.PlanningEventSubmissionPort;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class HazardReassessmentService {
    private static final int PAGE_SIZE = 100;
    private final ConsumerSelectionService selection;
    private final HazardReadService reads;
    private final HazardStateRepository hazards;
    private final DiagnosisOutboxRepository outbox;
    private final PlanningEventSubmissionPort codec;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public HazardReassessmentService(ConsumerSelectionService selection, HazardReadService reads,
            HazardStateRepository hazards, DiagnosisOutboxRepository outbox,
            PlanningEventSubmissionPort codec, Clock clock, PlatformTransactionManager transactionManager) {
        this.selection = selection;
        this.reads = reads;
        this.hazards = hazards;
        this.outbox = outbox;
        this.codec = codec;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public int enqueueExposedBuses() {
        var selected = selection.current();
        if (!"DEMO".equals(selected.mode()) || selected.demoRunId() == null) return 0;

        int queued = 0;
        for (int pageNumber = 0; ; pageNumber++) {
            var page = reads.list(null, pageNumber, PAGE_SIZE);
            for (var hazard : page.items()) {
                if (!"ACTIVE".equals(hazard.status()) || hazard.sourceObservationId() == null) continue;
                var progressByBus = new LinkedHashMap<String, Integer>();
                for (var exposure : hazard.exposures())
                    progressByBus.putIfAbsent(exposure.busId(), exposure.busProgressSequence());
                for (var entry : progressByBus.entrySet())
                    if (enqueue(hazard, selected.demoRunId(), entry.getKey(), entry.getValue())) queued++;
            }
            if ((long) (pageNumber + 1) * PAGE_SIZE >= page.totalElements()) break;
        }
        return queued;
    }

    private boolean enqueue(HazardReadService.View hazard, UUID runId, String busId, int progressSequence) {
        return transactions.execute(status -> {
            String revisionKey = hazard.sourceRevision() == null
                    ? "observation:" + hazard.sourceObservationId() : "revision:" + hazard.sourceRevision();
            String identity = "hazard-reassessment-v1:" + hazard.disruptionId() + ":" + revisionKey + ":"
                    + runId + ":" + busId + ":" + progressSequence;
            UUID eventId = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
            if (hazards.eventExists(eventId) || outbox.findByEventId(eventId).isPresent()) return false;

            var now = clock.instant();
            var snapshot = new HazardEventSnapshot(eventId, hazard.disruptionId(), "REASSESSED", hazard.inactiveReason(),
                    hazard.classification(), hazard.demoRunId(), runId, hazard.sourceObservationId(),
                    hazard.sourceRevision(), null, busId, now, hazard.facts(), null);
            var encoded = codec.encodeHazard(snapshot);
            if (hazards.recordEvent(eventId, hazard.disruptionId(), hazard.kind(), "REASSESSED", hazard.classification(),
                    hazard.demoRunId(), hazard.sourceObservationId(), null, now, now, encoded.payloadJson()) == 0) return false;
            outbox.save(new DiagnosisOutbox(eventId, eventId, encoded.payloadJson(), encoded.payloadFingerprint(),
                    encoded.schemaVersion(), now));
            return true;
        });
    }
}
