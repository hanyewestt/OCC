package com.csd.occ.disruption.bunching.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "A paginated page of currently open live bunching alerts")
public record LiveBunchingAlertPageResponse(List<LiveBunchingAlertResponse> items, int page, int size,
                                            long totalItems, int totalPages) {
    public LiveBunchingAlertPageResponse { items = List.copyOf(items); }
}
