package com.csd.occ.planning.diagnosis.domain;

public interface ContextEvidencePort {
    RootCauseContext findContext(BunchingEventFacts event);
}
