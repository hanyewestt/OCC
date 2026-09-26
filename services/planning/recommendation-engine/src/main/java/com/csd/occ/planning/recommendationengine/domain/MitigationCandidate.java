package com.csd.occ.planning.recommendationengine.domain;

import java.util.Objects;

public record MitigationCandidate(
        CandidateMitigationType mitigationType,
        String targetBusReference,
        String controlStopCode,
        int requestedAdjustmentSeconds,
        String rationale) {

    private static final int MAXIMUM_ADJUSTMENT_SECONDS = 120;
    private static final int MAXIMUM_BUS_REFERENCE_LENGTH = 64;
    private static final int MAXIMUM_STOP_CODE_LENGTH = 12;
    private static final int MAXIMUM_RATIONALE_LENGTH = 1_000;

    public MitigationCandidate {
        Objects.requireNonNull(mitigationType, "mitigationType is required");
        targetBusReference = requireText(
                targetBusReference,
                "targetBusReference",
                MAXIMUM_BUS_REFERENCE_LENGTH);
        controlStopCode = requireText(
                controlStopCode,
                "controlStopCode",
                MAXIMUM_STOP_CODE_LENGTH);
        if (requestedAdjustmentSeconds < 1 || requestedAdjustmentSeconds > MAXIMUM_ADJUSTMENT_SECONDS) {
            throw new IllegalArgumentException("requestedAdjustmentSeconds must be between one and 120");
        }
        rationale = requireText(rationale, "rationale", MAXIMUM_RATIONALE_LENGTH);
    }

    private static String requireText(String value, String fieldName, int maximumLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        if (value.length() > maximumLength) {
            throw new IllegalArgumentException(fieldName + " must not exceed " + maximumLength + " characters");
        }
        return value;
    }
}
