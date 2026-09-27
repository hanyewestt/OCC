package com.csd.occ.ingestion.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** Concrete OpenAPI representation of the paginated arrival envelope. */
@Schema(requiredProperties = {"items", "page", "size", "totalItems", "totalPages"})
public record ArrivalPageResponse(List<ArrivalResponse> items, int page, int size, long totalItems, int totalPages) {
}
