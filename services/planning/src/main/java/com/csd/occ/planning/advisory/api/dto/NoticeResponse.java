package com.csd.occ.planning.advisory.api.dto;

import com.csd.occ.planning.advisory.domain.PublicNotice;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record NoticeResponse(UUID id, UUID disruptionId, String classification,
        @io.swagger.v3.oas.annotations.media.Schema(nullable = true) UUID demoRunId,
        String title, String message, List<String> services, List<String> locations, Instant effectiveFrom,
        Instant effectiveUntil, String status,
        @io.swagger.v3.oas.annotations.media.Schema(nullable = true) Instant publishedAt,
        @io.swagger.v3.oas.annotations.media.Schema(nullable = true) Instant withdrawnAt) {
    public static NoticeResponse from(PublicNotice n) {
        return new NoticeResponse(n.id(), n.disruptionId(), n.classification(), n.demoRunId(), n.title(), n.message(),
                n.services(), n.locations(), n.effectiveFrom(), n.effectiveUntil(), n.status(), n.publishedAt(), n.withdrawnAt());
    }
    @io.swagger.v3.oas.annotations.media.Schema(name = "ManagedNoticePageResponse")
    public record PageResponse(List<NoticeResponse> items, int page, int size, long totalItems, int totalPages) {}
}
