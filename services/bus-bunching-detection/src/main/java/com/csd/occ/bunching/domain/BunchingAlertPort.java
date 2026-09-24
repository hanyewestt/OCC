package com.csd.occ.bunching.domain;

/** Persist alert transitions in the evaluation transaction. */
public interface BunchingAlertPort {
    void trigger(BunchingEvaluation evaluation);
}
