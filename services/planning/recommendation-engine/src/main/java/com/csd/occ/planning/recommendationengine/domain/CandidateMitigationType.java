package com.csd.occ.planning.recommendationengine.domain;

public enum CandidateMitigationType {
    HOLD,
    DWELL_ADJUSTMENT;

    public MitigationType toMitigationType() {
        return MitigationType.valueOf(name());
    }
}
