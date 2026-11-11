package com.csd.occ.ingestion.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(requiredProperties = {"items", "page", "size", "totalItems", "totalPages", "directorySynchronizedAt"})
public record DirectoryPageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages,
        @Schema(description = "Last successful complete provider scan; null before the first successful publication", nullable = true)
        Instant directorySynchronizedAt) { }
