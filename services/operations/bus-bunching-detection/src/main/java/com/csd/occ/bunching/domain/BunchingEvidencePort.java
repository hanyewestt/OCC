package com.csd.occ.bunching.domain;

/** Encodes immutable evaluation evidence for retained audit records. */
public interface BunchingEvidencePort {
    String encode(BunchingEvaluation evaluation);

    /** Encodes only the snapshot data and evaluation relevant to one alerting group. */
    String encodeAlert(BunchingEvaluation evaluation, BunchingEvaluation.GroupEvaluation group);
}
