package com.csd.occ.diagnosis.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RootCauseDiagnosis(UUID id, UUID requestId, String causeCode, String causeSubtype,
                                 String likelyCause, double confidence, double evidenceSupportScore,
                                 List<String> supportingEvidenceIds, List<String> limitations,
                                 ConfidenceComponents confidenceComponents, String evidenceSnapshot,
                                 String evidenceSnapshotHash, String provider, String model,
                                 String promptVersion, String schemaVersion, Instant completedAt) {
    public RootCauseDiagnosis {
        supportingEvidenceIds = List.copyOf(supportingEvidenceIds);
        limitations = List.copyOf(limitations);
    }
}
