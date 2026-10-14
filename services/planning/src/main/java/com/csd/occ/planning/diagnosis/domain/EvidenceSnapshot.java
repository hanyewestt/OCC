package com.csd.occ.planning.diagnosis.domain;

import java.util.List;

public record EvidenceSnapshot(BunchingEventFacts event, RootCauseContext context, List<String> allowedEvidenceIds,
                               String classification) {
    public EvidenceSnapshot {
        if (event == null) throw new EvidenceLimitExceededException("event");
        if (context == null) throw new EvidenceLimitExceededException("context");
        classification = EvidenceBounds.bounded(classification, 30, "classification");
        allowedEvidenceIds = EvidenceBounds.copyStrings(allowedEvidenceIds, 200, 200, "allowedEvidenceIds");
    }
}
