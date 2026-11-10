package com.csd.occ.planning.recommendation.api.dto;

import com.csd.occ.planning.recommendation.domain.MitigationType;

public enum ControllerReplacementAction {
    HOLD, DWELL_ADJUSTMENT, SKIP_STOP, NO_INSTRUCTION;

    public MitigationType mitigationType() {
        return this == NO_INSTRUCTION ? null : MitigationType.valueOf(name());
    }
}
