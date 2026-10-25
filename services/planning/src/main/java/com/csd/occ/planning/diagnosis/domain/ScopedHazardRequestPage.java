package com.csd.occ.planning.diagnosis.domain;

import java.util.List;
import java.util.UUID;

public record ScopedHazardRequestPage(String mode, UUID demoRunId, List<PlanningHazardRequestSummary> items,
        int page, int size, long totalItems, int totalPages) {
    public ScopedHazardRequestPage { items = List.copyOf(items); }
}
