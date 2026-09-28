package com.csd.occ.diagnosis.domain;

import java.util.List;

public record ModelOutput(String causeCode, String causeSubtype, String likelyCause,
                          List<String> supportingEvidenceIds, List<String> limitations) {
    public ModelOutput {
        supportingEvidenceIds = List.copyOf(supportingEvidenceIds);
        limitations = List.copyOf(limitations);
    }
}
