package com.csd.occ.planning.recommendationengine.domain;

import java.util.List;
import java.util.Objects;

public record ValidatedMitigationContext(
        String followingBusReference,
        String approvedControlStopCode,
        FollowingBusState followingBusState,
        int maximumHoldSeconds,
        int maximumDwellAdjustmentSeconds,
        CrowdSafetyStatus crowdSafetyStatus,
        MitigationValidationStatus validationStatus,
        List<String> evidenceIds) {

    public ValidatedMitigationContext {
        followingBusReference = requireNonBlank(followingBusReference, "followingBusReference");
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

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }
}
