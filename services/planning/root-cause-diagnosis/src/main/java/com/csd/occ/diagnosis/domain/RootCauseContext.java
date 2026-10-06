package com.csd.occ.diagnosis.domain;

import java.util.List;
import java.util.UUID;

public record RootCauseContext(String classification, UUID demoRunId, UUID referenceVersionId,
                               List<ArrivalEvidence> arrivals, List<IncidentEvidence> incidents,
                               List<String> missingFields) {
    public RootCauseContext {
        classification = EvidenceBounds.bounded(classification, 30, "classification");
        arrivals = EvidenceBounds.copy(arrivals, 20, "arrivals");
        incidents = EvidenceBounds.copy(incidents, 50, "incidents");
        missingFields = EvidenceBounds.copyStrings(missingFields, 20, 100, "missingFields");
    }
}
