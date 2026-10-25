package com.csd.occ.planning.recommendation.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Paginated instructions for one simulation bus, newest first")
public record BusInstructionPageResponse(
        List<BusInstructionResponse> items,
        int page,
        int size,
        long totalItems,
        int totalPages) {
}
