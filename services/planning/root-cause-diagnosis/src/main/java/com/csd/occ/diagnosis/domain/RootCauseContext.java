package com.csd.occ.diagnosis.domain;

import java.util.List;
import java.util.UUID;

public record RootCauseContext(String classification, UUID demoRunId, UUID referenceVersionId,
                               List<ArrivalEvidence> arrivals, List<IncidentEvidence> incidents,
                               List<String> missingFields) {
    public RootCauseContext {
        arrivals = List.copyOf(arrivals);
        incidents = List.copyOf(incidents);
        missingFields = List.copyOf(missingFields);
    }
}
