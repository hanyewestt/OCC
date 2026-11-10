package com.csd.occ.planning.recommendation.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReplacementBusInstructionRequest(
        @NotBlank @Size(max = 80) String busReference,
        @NotNull ControllerReplacementAction action,
        @Min(1) @Max(120) Integer adjustmentSeconds,
        @Size(max = 480) String noInstructionReason) {
    public ReplacementBusInstructionRequest {
        if (action == ControllerReplacementAction.NO_INSTRUCTION) {
            if (adjustmentSeconds != null || noInstructionReason == null || noInstructionReason.isBlank())
                throw new IllegalArgumentException("No instruction requires a reason and no duration");
        } else if (action == ControllerReplacementAction.SKIP_STOP) {
            if (adjustmentSeconds != null || noInstructionReason != null)
                throw new IllegalArgumentException("Skip stop does not accept a duration or no-instruction reason");
        } else if (action != null && (adjustmentSeconds == null || noInstructionReason != null)) {
            throw new IllegalArgumentException("A movement instruction requires a duration and no no-instruction reason");
        }
    }
}
