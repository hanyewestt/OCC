package com.csd.occ.disruption.bunching.domain;

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
    public DiagnosisOutboxService(DiagnosisOutboxRepository outbox, Clock clock) { this.outbox = outbox; this.clock = clock; }

    @Transactional
    public List<DiagnosisOutboxClaim> claimDue(int batchSize, java.time.Duration leaseDuration) {
        Instant now = clock.instant();
        return outbox.findClaimable(now, batchSize).stream().map(item -> {
            item.claimed(now.plus(leaseDuration));
            return new DiagnosisOutboxClaim(item.id(), item.eventId(), item.attemptCount());
        }).toList();
    }

    @Transactional
    public void delivered(UUID id, Integer status) {
        outbox.findById(id).ifPresent(item -> item.delivered(status, clock.instant()));
    }
    @Transactional
    public void retry(UUID id, Integer status, String code, Instant retryAt) {
        outbox.findById(id).ifPresent(item -> item.retry(status, code, retryAt));
    }
    @Transactional
    public void fail(UUID id, Integer status, String code) {
        outbox.findById(id).ifPresent(item -> item.fail(status, code));
    }
}
