package com.csd.occ.ingestion.api.dto;

import java.util.List;
import java.util.UUID;

public record SimulationBunchingGroupResponse(UUID demoRunId, UUID bunchingEventId, String serviceId,
        int directionId, String stopCode, List<String> affectedBusReferences, String followingBusReference) {
    public SimulationBunchingGroupResponse {
        affectedBusReferences = List.copyOf(affectedBusReferences);
    }
}
