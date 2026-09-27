package com.csd.occ.ingestion.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "The opening alert-event data for one currently active live bunching detection")
public record LiveBunchingAlertResponse(UUID eventId, UUID alertId, String action, String classification,
                                        String serviceId, String stopCode, int directionId, Instant detectedAt,
                                        Instant fetchedAt, UUID rawObservationId, UUID pollRunId, String evidence) {}
