package com.csd.occ.disruption.hazard.api.dto;

import com.csd.occ.disruption.hazard.domain.HazardReadService;
import com.csd.occ.disruption.hazard.domain.HazardPollPort;
import com.csd.occ.disruption.hazard.domain.HazardExposurePolicy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record HazardResponse(UUID disruptionId, String kind, String status, String inactiveReason,
        String classification, UUID demoRunId, UUID sourceObservationId, Instant updatedAt, HazardPollPort.Fact facts,
        List<HazardExposurePolicy.Exposure> exposures, List<String> limitations) {
    public static HazardResponse from(HazardReadService.View view) {
        return new HazardResponse(view.disruptionId(), view.kind(), view.status(), view.inactiveReason(), view.classification(),
                view.demoRunId(), view.sourceObservationId(), view.updatedAt(), view.facts(), view.exposures(), view.limitations());
    }
    public record PageResponse(List<HazardResponse> items, int page, int size, long totalElements,
                               List<com.csd.occ.disruption.hazard.domain.HazardCheckpoint.FeedHealth> feeds) {}
}
