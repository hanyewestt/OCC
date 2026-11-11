package com.csd.occ.planning.domain.audit;

import com.csd.occ.planning.advisory.domain.AdvisoryModelEvidenceRepository;
import com.csd.occ.planning.diagnosis.domain.ModelInvocationRecordRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApplicationAuditRetentionService {
    private final ApplicationAuditEventRepository events;
    private final ApplicationAuditMetadataRepository metadata;
    private final ModelInvocationRecordRepository invocations;
    private final AdvisoryModelEvidenceRepository advisoryEvidence;
    private final Clock clock;
    public ApplicationAuditRetentionService(ApplicationAuditEventRepository events, ApplicationAuditMetadataRepository metadata,
            ModelInvocationRecordRepository invocations, AdvisoryModelEvidenceRepository advisoryEvidence, Clock clock) {
        this.events = events; this.metadata = metadata; this.invocations = invocations;
        this.advisoryEvidence = advisoryEvidence; this.clock = clock;
    }
    @Transactional public Instant collectionStartedAt() {
        metadata.initialize(clock.instant());
        return metadata.findById((short) 1).orElseThrow().collectionStartedAt();
    }
    @Transactional public int purgeExpired(Duration retention) {
        if (retention == null || retention.isZero() || retention.isNegative()) throw new IllegalArgumentException("Audit retention must be positive");
        var cutoff = clock.instant().minus(retention);
        return events.deleteBefore(cutoff) + invocations.deleteExpiredBefore(cutoff)
                + advisoryEvidence.deleteExpiredBefore(cutoff);
    }
}
