package com.csd.occ.ingestion.domain;

import java.time.Instant;
import java.util.UUID;

public record DemoBunchingAlert(String action, String serviceId, String stopCode, int directionId,
                                Instant detectedAt, String classification, UUID leadingObservationId,
                                UUID followingObservationId) {}
