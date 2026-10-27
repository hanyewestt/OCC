package com.csd.occ.planning.advisory.api.dto;

import com.csd.occ.planning.advisory.domain.PublicNotice;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Passenger-facing allowlist: no actors, fleet identities, evidence payloads or private rationale. */
public record PublishedNoticeResponse(UUID id, String classification, String title, String message,
        List<String> services, List<String> locations, Instant effectiveFrom, Instant effectiveUntil, Instant publishedAt) {
    public static PublishedNoticeResponse from(PublicNotice n) {
        return new PublishedNoticeResponse(n.id(), n.classification(), n.title(), n.message(), n.services(), n.locations(),
                n.effectiveFrom(), n.effectiveUntil(), n.publishedAt());
    }
    @io.swagger.v3.oas.annotations.media.Schema(name = "PublishedNoticePageResponse")
    public record PageResponse(List<PublishedNoticeResponse> items, int page, int size, long totalItems, int totalPages) {}
}
