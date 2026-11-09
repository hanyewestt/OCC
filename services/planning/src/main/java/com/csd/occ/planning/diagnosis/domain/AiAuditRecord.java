package com.csd.occ.planning.diagnosis.domain;

import java.time.Instant;
import java.util.UUID;

/** Safe, controller-facing metadata for one retained AI invocation. */
public record AiAuditRecord(UUID invocationId, String purpose, UUID requestId, UUID recommendationId,
                            String provider, String model, String promptVersion, String schemaVersion,
                            String attemptType, Integer attemptNumber, Instant startedAt, Instant completedAt,
                            long latencyMillis, Integer promptTokens, Integer completionTokens, Integer totalTokens,
                            String outcome, String failureCategory, Boolean deterministicFallbackUsed) {
    static AiAuditRecord from(ModelInvocationRecord record) {
        return new AiAuditRecord(record.id(), record.purpose(), record.requestId(), record.recommendationId(),
                record.provider(), record.configuredModel(), record.promptVersion(), record.schemaVersion(),
                record.invocationType(), record.attemptNumber(), record.startedAt(), record.completedAt(),
                record.latencyMillis(), record.promptTokens(), record.completionTokens(), record.totalTokens(),
                record.validationOutcome(), record.failureCategory(), record.deterministicFallbackUsed());
    }
}
