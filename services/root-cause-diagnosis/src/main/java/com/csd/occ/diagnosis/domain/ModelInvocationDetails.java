package com.csd.occ.diagnosis.domain;

import java.time.Instant;

public record ModelInvocationDetails(String provider, String model, String responseId, String promptVersion,
                                     String schemaVersion, Instant startedAt, Instant completedAt,
                                     long latencyMillis, Integer promptTokens, Integer completionTokens,
                                     Integer totalTokens, String validationOutcome, String failureCategory,
                                     int attemptNumber) {
}
