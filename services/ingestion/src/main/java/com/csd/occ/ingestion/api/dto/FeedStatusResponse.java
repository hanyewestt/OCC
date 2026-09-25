package com.csd.occ.ingestion.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(requiredProperties = {"source", "scopeKey", "status", "lastAttemptAt", "lastSuccessAt", "consecutiveFailures",
        "staleAfterSeconds", "message", "updatedAt", "stale"})
public record FeedStatusResponse(String source, String scopeKey, String status, @Schema(nullable = true) Instant lastAttemptAt,
        @Schema(nullable = true) Instant lastSuccessAt, Integer consecutiveFailures, Integer staleAfterSeconds,
        @Schema(nullable = true) String message,
        Instant updatedAt, boolean stale) {
}
