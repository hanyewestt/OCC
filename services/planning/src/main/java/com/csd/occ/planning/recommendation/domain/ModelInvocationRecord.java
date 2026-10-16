package com.csd.occ.planning.recommendation.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record ModelInvocationRecord(
        UUID recommendationId,
        ModelInvocationMetadata metadata,
        ModelInvocationOutcome outcome,
        Optional<MitigationCandidate> candidate,
        Optional<MitigationCandidateRejectionReason> rejectionReason,
        boolean deterministicFallbackUsed,
        Optional<ModelInvocationFailureCategory> failureCategory) {

    public ModelInvocationRecord {
        Objects.requireNonNull(recommendationId, "recommendationId is required");
        Objects.requireNonNull(metadata, "metadata is required");
        Objects.requireNonNull(outcome, "outcome is required");
        Objects.requireNonNull(candidate, "candidate is required");
        Objects.requireNonNull(rejectionReason, "rejectionReason is required");
        Objects.requireNonNull(failureCategory, "failureCategory is required");
        validateOutcome(outcome, candidate, rejectionReason, deterministicFallbackUsed, failureCategory);
    }

    private static void validateOutcome(
            ModelInvocationOutcome outcome,
            Optional<MitigationCandidate> candidate,
            Optional<MitigationCandidateRejectionReason> rejectionReason,
            boolean fallbackUsed,
            Optional<ModelInvocationFailureCategory> failureCategory) {
        switch (outcome) {
            case ACCEPTED -> requireState(candidate.isPresent() && rejectionReason.isEmpty()
                    && failureCategory.isEmpty() && !fallbackUsed, "accepted invocation state is inconsistent");
            case REJECTED -> requireState(candidate.isPresent() && rejectionReason.isPresent()
                    && failureCategory.isEmpty() && fallbackUsed, "rejected invocation state is inconsistent");
            case MISMATCHED -> requireState(candidate.isPresent() && rejectionReason.isEmpty()
                    && failureCategory.isEmpty() && fallbackUsed, "mismatched invocation state is inconsistent");
            case EMPTY_RESPONSE -> requireState(candidate.isEmpty() && rejectionReason.isEmpty()
                    && failureCategory.isEmpty() && fallbackUsed, "empty invocation state is inconsistent");
            case PROVIDER_FAILURE -> requireState(candidate.isEmpty() && rejectionReason.isEmpty()
                    && failureCategory.isPresent() && fallbackUsed, "failed invocation state is inconsistent");
        }
    }

    private static void requireState(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }
}
