package com.csd.occ.ingestion.api.dto;

import java.time.Instant;

public record FeedStatusResponse(String source, String scopeKey, String status, Instant lastAttemptAt,
        Instant lastSuccessAt, Integer consecutiveFailures, Integer staleAfterSeconds, String message,
        Instant updatedAt, boolean stale) {
}
