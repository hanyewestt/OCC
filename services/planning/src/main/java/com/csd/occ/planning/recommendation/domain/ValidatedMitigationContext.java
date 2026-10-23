package com.csd.occ.planning.recommendation.domain;

import java.util.List;
import java.util.Objects;

public record ValidatedMitigationContext(
        String followingBusReference,
        List<String> affectedBusReferences,
        String approvedControlStopCode,
        FollowingBusState followingBusState,
        int maximumHoldSeconds,
        int maximumDwellAdjustmentSeconds,
        CrowdSafetyStatus crowdSafetyStatus,
        MitigationValidationStatus validationStatus,
        List<String> evidenceIds) {

    public ValidatedMitigationContext {
        followingBusReference = requireNonBlank(followingBusReference, "followingBusReference");
        // Recommendations saved before the queue supported grouped bus events do not
        // contain this field. Retain those records as single-bus contexts while all
        // newly generated bunching contexts include the complete two- or three-bus set.
        affectedBusReferences = affectedBusReferences == null || affectedBusReferences.isEmpty()
                ? List.of(followingBusReference)
                : List.copyOf(affectedBusReferences);
        if (affectedBusReferences.size() > 3
                || affectedBusReferences.stream().anyMatch(reference -> reference == null || reference.isBlank())
                || !affectedBusReferences.contains(followingBusReference)) {
            throw new IllegalArgumentException("affectedBusReferences must contain up to three buses including the target bus");
        }
        approvedControlStopCode = requireNonBlank(approvedControlStopCode, "approvedControlStopCode");
        Objects.requireNonNull(followingBusState, "followingBusState is required");
        if (maximumHoldSeconds < 0 || maximumDwellAdjustmentSeconds < 0) {
            throw new IllegalArgumentException("maximum adjustment seconds must not be negative");
        }
        Objects.requireNonNull(crowdSafetyStatus, "crowdSafetyStatus is required");
        Objects.requireNonNull(validationStatus, "validationStatus is required");
        evidenceIds = List.copyOf(evidenceIds);
        if (evidenceIds.isEmpty()) {
            throw new IllegalArgumentException("evidenceIds must not be empty");
        }
    }

    public ValidatedMitigationContext(String followingBusReference, String approvedControlStopCode,
            FollowingBusState followingBusState, int maximumHoldSeconds,
            int maximumDwellAdjustmentSeconds, CrowdSafetyStatus crowdSafetyStatus,
            MitigationValidationStatus validationStatus, List<String> evidenceIds) {
        this(followingBusReference, List.of(followingBusReference), approvedControlStopCode,
                followingBusState, maximumHoldSeconds, maximumDwellAdjustmentSeconds,
                crowdSafetyStatus, validationStatus, evidenceIds);
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }
}
