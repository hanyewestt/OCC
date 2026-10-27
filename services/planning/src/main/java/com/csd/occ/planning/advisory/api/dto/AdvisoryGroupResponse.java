package com.csd.occ.planning.advisory.api.dto;

import com.csd.occ.planning.advisory.domain.AdvisoryCandidate;
import com.csd.occ.planning.advisory.domain.AdvisoryGroup;
import java.util.List;
import java.util.UUID;

public record AdvisoryGroupResponse(UUID id, UUID disruptionId, UUID demoRunId, String serviceId, int directionId,
                                    String hazardTitle, String hazardClassification, AdvisoryCandidate candidate,
                                    String preview, List<AdvisoryResponse> members) {
    public static AdvisoryGroupResponse from(AdvisoryGroup group) {
        var first = group.members().getFirst();
        return new AdvisoryGroupResponse(first.id(), group.key().disruptionId(), group.key().demoRunId(),
                group.key().serviceId(), group.key().directionId(), first.context().facts().title(),
                first.context().classification(), first.candidate(), first.preview(),
                group.members().stream().map(AdvisoryResponse::from).toList());
    }
    @io.swagger.v3.oas.annotations.media.Schema(name = "AdvisoryGroupPageResponse")
    public record PageResponse(List<AdvisoryGroupResponse> items, int page, int size, long totalItems, int totalPages) {}
}
