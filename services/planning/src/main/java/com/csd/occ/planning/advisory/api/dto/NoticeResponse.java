package com.csd.occ.planning.advisory.api.dto;

import com.csd.occ.planning.advisory.domain.PublicNotice;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record NoticeResponse(UUID id, UUID disruptionId, String classification, UUID demoRunId,
        String title, String message, List<String> services, List<String> locations, Instant effectiveFrom,
        Instant effectiveUntil, String status, Instant publishedAt, Instant withdrawnAt) {
    public static NoticeResponse from(PublicNotice n) {
        return new NoticeResponse(n.id(), n.disruptionId(), n.classification(), n.demoRunId(), n.title(), n.message(),
                n.services(), n.locations(), n.effectiveFrom(), n.effectiveUntil(), n.status(), n.publishedAt(), n.withdrawnAt());
    }
    public record PageResponse(List<NoticeResponse> items, int page, int size, long totalItems, int totalPages) {}
}
