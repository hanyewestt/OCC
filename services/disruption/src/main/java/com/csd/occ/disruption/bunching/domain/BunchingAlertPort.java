package com.csd.occ.disruption.bunching.domain;

/** Persist alert transitions in the evaluation transaction. */
public interface BunchingAlertPort {
    void trigger(BunchingEvaluation evaluation);
}
