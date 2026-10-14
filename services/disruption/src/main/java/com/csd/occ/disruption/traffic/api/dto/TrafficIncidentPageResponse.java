package com.csd.occ.disruption.traffic.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "A paginated page of active traffic incidents")
public record TrafficIncidentPageResponse(
        List<TrafficIncidentResponse> items,
        int page,
        int size,
        long totalItems,
        int totalPages) {
    public TrafficIncidentPageResponse { items = List.copyOf(items); }
}
