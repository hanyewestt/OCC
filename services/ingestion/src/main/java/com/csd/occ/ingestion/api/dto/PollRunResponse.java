package com.csd.occ.ingestion.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(requiredProperties = {"id", "source", "scopeKey", "startedAt", "completedAt", "outcome", "httpStatus",
        "recordsReceived", "errorCode", "errorMessage"})
public record PollRunResponse(UUID id, String source, String scopeKey, Instant startedAt,
        @Schema(nullable = true) Instant completedAt, String outcome, @Schema(nullable = true) Integer httpStatus,
        Integer recordsReceived, @Schema(nullable = true) String errorCode, @Schema(nullable = true) String errorMessage) {
}
