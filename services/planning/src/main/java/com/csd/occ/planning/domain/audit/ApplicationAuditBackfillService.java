package com.csd.occ.planning.domain.audit;

import com.csd.occ.planning.diagnosis.domain.ModelInvocationRecord;
import com.csd.occ.planning.diagnosis.domain.ModelInvocationRecordRepository;
import com.csd.occ.planning.advisory.domain.AdvisoryModelEvidence;
import com.csd.occ.planning.advisory.domain.AdvisoryModelEvidenceRepository;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApplicationAuditBackfillService {
    private static final int BATCH_SIZE = 250;
    private final ModelInvocationRecordRepository legacy;
    private final AdvisoryModelEvidenceRepository advisoryEvidence;
    private final ApplicationAuditEventRepository events;
    private final ApplicationAuditMetadataRepository metadata;
    private final Clock clock;
    private final Duration retention;

    public ApplicationAuditBackfillService(ModelInvocationRecordRepository legacy,
            AdvisoryModelEvidenceRepository advisoryEvidence,
            ApplicationAuditEventRepository events, ApplicationAuditMetadataRepository metadata, Clock clock,
            AuditRetentionPolicy retention) {
        this.legacy = legacy; this.advisoryEvidence = advisoryEvidence; this.events = events;
        this.metadata = metadata; this.clock = clock; this.retention = retention.retention();
    }

    @Transactional
    public int backfillRetainedModelInvocations() {
        metadata.initialize(clock.instant());
        metadata.lockSingleton().orElseThrow();
        Instant cutoff = clock.instant().minus(retention);
        int copied = 0;
        Page<ModelInvocationRecord> page;
        int number = 0;
        do {
            page = legacy.findAllByOrderByIdAsc(PageRequest.of(number++, BATCH_SIZE));
            for (ModelInvocationRecord invocation : page.getContent()) {
                String sourceKey = "legacy-model-invocation:" + invocation.id();
                if (events.existsBySourceKey(sourceKey) || events.existsById(invocation.id())) continue;
                Instant occurred = invocation.completedAt() == null ? invocation.startedAt() : invocation.completedAt();
                if (occurred == null) occurred = clock.instant();
                if (occurred.isBefore(cutoff)) continue;
                var details = new LinkedHashMap<String, Object>();
                details.put("provider", invocation.provider()); details.put("model", invocation.configuredModel());
                details.put("purpose", invocation.purpose()); details.put("promptVersion", invocation.promptVersion());
                details.put("schemaVersion", invocation.schemaVersion()); details.put("attempt", invocation.attemptNumber());
                details.put("startedAt", invocation.startedAt() == null ? null : invocation.startedAt().toString());
                details.put("completedAt", invocation.completedAt() == null ? null : invocation.completedAt().toString());
                details.put("latencyMillis", invocation.latencyMillis()); details.put("promptTokens", invocation.promptTokens());
                details.put("completionTokens", invocation.completionTokens()); details.put("totalTokens", invocation.totalTokens());
                details.put("outcome", invocation.validationOutcome()); details.put("failureCategory", invocation.failureCategory());
                details.put("input", invocation.inputSnapshot()); details.put("output", invocation.generatedResponse());
                UUID correlation = invocation.correlationId() == null ? invocation.requestId() : invocation.correlationId();
                var event = new ApplicationAuditEvent(invocation.id(), occurred, clock.instant(), "AI_PROCESSING",
                        "AI_MODEL_INVOCATION_BACKFILLED", invocation.validationOutcome() == null ? "UNKNOWN" : invocation.validationOutcome(), null, "SYSTEM",
                        "MODEL_INVOCATION", invocation.id().toString(), null, null, null,
                        correlation == null ? null : correlation.toString(), "Retained model invocation backfilled",
                        details, sourceKey);
                event.markBackfilled(); events.save(event); copied++;
            }
        } while (page.hasNext());
        number = 0;
        Page<AdvisoryModelEvidence> evidencePage;
        do {
            evidencePage = advisoryEvidence.findAllByOrderByProposalIdAsc(PageRequest.of(number++, BATCH_SIZE));
            for (AdvisoryModelEvidence evidence : evidencePage.getContent()) {
                String sourceKey = "legacy-advisory-evidence:" + evidence.proposalId();
                // Event IDs correlate sibling proposals, so evidence events use a stable proposal-specific namespace.
                UUID id = UUID.nameUUIDFromBytes(sourceKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (events.existsBySourceKey(sourceKey) || events.existsById(id)) continue;
                if (evidence.createdAt().isBefore(cutoff)) continue;
                var details = new LinkedHashMap<String, Object>();
                details.put("model", evidence.model()); details.put("input", evidence.modelInput());
                details.put("output", evidence.modelOutput()); details.put("candidate", evidence.candidate());
                details.put("finalCandidate", evidence.finalCandidate());
                details.put("controllerRationale", evidence.controllerRationale());
                details.put("replacedInstructionId", evidence.replacedInstructionId());
                details.put("decidedAt", evidence.decidedAt() == null ? null : evidence.decidedAt().toString());
                var event = new ApplicationAuditEvent(id, evidence.createdAt(), clock.instant(),
                        "ADVISORIES", "ADVISORY_EVIDENCE_BACKFILLED", "BACKFILLED", evidence.controllerSubject(),
                        evidence.controllerSubject() == null ? "SYSTEM" : "CONTROLLER", "ADVISORY_PROPOSAL",
                        evidence.proposalId().toString(), evidence.demoRunId(), evidence.busId(), null,
                        evidence.eventId() == null ? null : evidence.eventId().toString(),
                        "Retained advisory evidence backfilled", details, sourceKey);
                event.markBackfilled(); events.save(event); copied++;
            }
        } while (evidencePage.hasNext());
        return copied;
    }
}
