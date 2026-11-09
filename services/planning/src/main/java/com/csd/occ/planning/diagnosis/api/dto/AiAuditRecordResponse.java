package com.csd.occ.planning.diagnosis.api.dto;

import com.csd.occ.planning.diagnosis.domain.AiAuditRecord;
import java.time.Instant;
import java.util.UUID;

public record AiAuditRecordResponse(UUID invocationId, String purpose, UUID requestId, UUID recommendationId,
        String provider, String model, String promptVersion, String schemaVersion, String attemptType,
        Integer attemptNumber, Instant startedAt, Instant completedAt, long latencyMillis, Integer promptTokens,
        Integer completionTokens, Integer totalTokens, String outcome, String failureCategory,
        Boolean deterministicFallbackUsed) {
    public static AiAuditRecordResponse from(AiAuditRecord record) {
        return new AiAuditRecordResponse(record.invocationId(), record.purpose(), record.requestId(),
                record.recommendationId(), record.provider(), record.model(), record.promptVersion(),
                record.schemaVersion(), record.attemptType(), record.attemptNumber(), record.startedAt(),
                record.completedAt(), record.latencyMillis(), record.promptTokens(), record.completionTokens(),
                record.totalTokens(), record.outcome(), record.failureCategory(), record.deterministicFallbackUsed());
    }
}
