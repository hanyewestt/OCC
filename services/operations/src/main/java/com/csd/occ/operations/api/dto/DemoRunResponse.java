package com.csd.occ.operations.api.dto;

import java.time.Instant;
import java.util.UUID;

public record DemoRunResponse(UUID id, String serviceId, int directionId, String status,
        Instant createdAt, String classification) { }
