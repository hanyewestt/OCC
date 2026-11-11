package com.csd.occ.disruption.bunching.domain;

import com.csd.occ.disruption.domain.audit.ApplicationAuditRecorder;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DiagnosisOutboxService {
    private final DiagnosisOutboxRepository outbox;
    private final Clock clock;
    private final ApplicationAuditRecorder audit;
    public DiagnosisOutboxService(DiagnosisOutboxRepository outbox, Clock clock, ApplicationAuditRecorder audit) {
        this.outbox = outbox; this.clock = clock; this.audit = audit;
    }

    @Transactional
    public List<DiagnosisOutboxClaim> claimDue(int batchSize, java.time.Duration leaseDuration) {
        Instant now = clock.instant();
        return outbox.findClaimable(now, batchSize).stream().map(item -> {
            item.claimed(now.plus(leaseDuration));
            return new DiagnosisOutboxClaim(item.id(), item.eventId(), item.attemptCount(), item.payloadJson(),
                    item.payloadFingerprint(), item.schemaVersion());
        }).toList();
    }

    @Transactional
    public void delivered(UUID id, Integer status) {
        outbox.findById(id).ifPresent(item -> {
            Instant now = clock.instant();
            item.delivered(status, now);
            audit.planningSubmission(id, item.attemptCount(), null, now, "DELIVERED", status, null);
        });
    }
    @Transactional
    public void retry(UUID id, Integer status, String code, Instant retryAt) {
        outbox.findById(id).ifPresent(item -> {
            item.retry(status, code, retryAt);
            audit.planningSubmission(id, item.attemptCount(), null, clock.instant(), "RETRY", status, code);
        });
    }
    @Transactional
    public void fail(UUID id, Integer status, String code) {
        outbox.findById(id).ifPresent(item -> {
            item.fail(status, code);
            audit.planningSubmission(id, item.attemptCount(), null, clock.instant(), "FAILED", status, code);
        });
    }
    @Transactional
    public String retainFingerprintIfAbsent(UUID id, String fingerprint) {
        DiagnosisOutbox item = outbox.findById(id).orElseThrow();
        item.retainFingerprint(fingerprint);
        return item.payloadFingerprint();
    }
}
