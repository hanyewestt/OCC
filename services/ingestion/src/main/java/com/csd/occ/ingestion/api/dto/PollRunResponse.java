package com.csd.occ.ingestion.api.dto;

import java.time.Instant;
import java.util.UUID;

public record PollRunResponse(UUID id, String source, String scopeKey, Instant startedAt, Instant completedAt,
        String outcome, Integer httpStatus, Integer recordsReceived, String errorCode, String errorMessage) {
}
