package com.csd.occ.planning.advisory.api.dto;

import com.csd.occ.planning.advisory.domain.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdvisoryResponse(UUID id, UUID disruptionId, UUID demoRunId, String busId, String status,
                               AdvisoryCandidate candidate,
                               @io.swagger.v3.oas.annotations.media.Schema(nullable = true) AdvisoryCandidate finalCandidate,
                               @io.swagger.v3.oas.annotations.media.Schema(nullable = true) String preview,
                               String hazardClassification, List<String> limitations, Instant createdAt) {
    public static AdvisoryResponse from(AdvisoryProposal value) {
        return new AdvisoryResponse(value.id(), value.disruptionId(), value.demoRunId(), value.busId(), value.status(),
                value.candidate(), value.finalCandidate(), value.preview(), value.context().classification(),
                value.context().limitations(), value.createdAt());
    }
    @io.swagger.v3.oas.annotations.media.Schema(name = "AdvisoryPageResponse")
    public record PageResponse(List<AdvisoryResponse> items, int page, int size, long totalItems, int totalPages) {}
}
