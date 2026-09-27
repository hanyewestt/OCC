package com.csd.occ.planning.recommendationengine.domain;

import java.util.Objects;

public sealed interface MitigationCandidateValidationResult
        permits MitigationCandidateValidationResult.Accepted, MitigationCandidateValidationResult.Rejected {

    MitigationCandidate candidate();

    record Accepted(
            MitigationCandidate candidate,
            int maximumAllowedAdjustmentSeconds) implements MitigationCandidateValidationResult {

        public Accepted {
            Objects.requireNonNull(candidate, "candidate is required");
            if (maximumAllowedAdjustmentSeconds < 1 || maximumAllowedAdjustmentSeconds > 120) {
                throw new IllegalArgumentException(
                        "maximumAllowedAdjustmentSeconds must be between one and 120");
            }
        }
    }

    record Rejected(
            MitigationCandidate candidate,
            MitigationCandidateRejectionReason reason) implements MitigationCandidateValidationResult {

        public Rejected {
            Objects.requireNonNull(candidate, "candidate is required");
            Objects.requireNonNull(reason, "reason is required");
        }
    }
}
