package com.csd.occ.operations.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(requiredProperties = {"items", "page", "size", "totalItems", "totalPages"})
public record DemoVehiclePageResponse(List<DemoVehicleResponse> items, int page, int size,
        long totalItems, int totalPages) { }
