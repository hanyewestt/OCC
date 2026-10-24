package com.csd.occ.planning.diagnosis.domain;

import java.util.List;
import java.util.UUID;

public record ScopedDiagnosisPage(String mode, UUID demoRunId, List<PlanningDiagnosisSummary> items,
        int page, int size, long totalItems, int totalPages) {
    public ScopedDiagnosisPage { items = List.copyOf(items); }
}
