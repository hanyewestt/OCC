package com.csd.occ.diagnosis.domain;

public interface ContextEvidencePort {
    RootCauseContext findContext(BunchingEventFacts event);
}
