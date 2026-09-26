package com.csd.occ.ingestion.domain;

import java.time.Instant;
import java.util.UUID;

public record LiveBunchingAlert(UUID eventId, UUID alertId, String action, String classification, String serviceId,
                                String stopCode, int directionId, Instant detectedAt, Instant fetchedAt,
                                UUID rawObservationId, UUID pollRunId, String evidence) {}
