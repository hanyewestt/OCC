package com.csd.occ.bunching.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Immutable canonical facts recorded by WQAP-2 for one alert event")
public record BunchingEventFactsResponse(UUID id, UUID bunchingEventId, String action, String classification,
        UUID demoRunId, String serviceId, int directionId, String monitoredStopCode,
        UUID leadingObservationId, UUID followingObservationId, List<UUID> confirmingObservationIds,
        String status, Integer expectedHeadwaySeconds, List<Integer> observedHeadwaySeconds,
        Instant firstConfirmedAt, List<Instant> confirmingObservationTimes,
        List<String> evidenceIds, String detectionRuleVersion) {
    public BunchingEventFactsResponse {
        confirmingObservationIds = List.copyOf(confirmingObservationIds);
        observedHeadwaySeconds = List.copyOf(observedHeadwaySeconds);
        confirmingObservationTimes = List.copyOf(confirmingObservationTimes);
        evidenceIds = List.copyOf(evidenceIds);
    }
}
