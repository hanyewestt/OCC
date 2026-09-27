package com.csd.occ.bunching.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "The OPENED alert event for a bunching episode that is currently active in the live feed")
public record LiveBunchingAlertResponse(UUID eventId, UUID alertId, String action, String classification,
                                        String serviceId, String stopCode, int directionId, Instant detectedAt,
                                        Instant fetchedAt, UUID rawObservationId, UUID pollRunId, String evidence) {}
