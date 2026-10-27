package com.csd.occ.planning.diagnosis.domain;

import java.util.List;
import java.util.UUID;

public record ScopedHazardHistoryPage(String mode, UUID demoRunId, List<PlanningHazardHistorySummary> items,
        int page, int size, long totalItems, int totalPages) {
    public ScopedHazardHistoryPage { items = List.copyOf(items); }
}
