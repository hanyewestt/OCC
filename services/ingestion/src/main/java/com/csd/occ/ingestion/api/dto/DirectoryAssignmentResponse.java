package com.csd.occ.ingestion.api.dto;

import java.util.UUID;

public record DirectoryAssignmentResponse(UUID demoRunId, String busId, String vehicleRegistrationNumber,
        String serviceId, int directionId) { }
