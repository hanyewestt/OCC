package com.csd.occ.diagnosis.domain;

import java.util.List;

public record EvidenceSnapshot(BunchingEventFacts event, RootCauseContext context, List<String> allowedEvidenceIds,
                               String classification) {
    public EvidenceSnapshot {
        allowedEvidenceIds = List.copyOf(allowedEvidenceIds);
    }
}
