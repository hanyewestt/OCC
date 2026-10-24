package com.csd.occ.planning.recommendation.api.dto;

import com.csd.occ.planning.recommendation.domain.ControllerBusInstruction;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Persisted controller-authored instruction for one affected bus")
public record ControllerBusInstructionResponse(String busReference, String instruction) {
    public static ControllerBusInstructionResponse from(ControllerBusInstruction instruction) {
        return new ControllerBusInstructionResponse(instruction.busReference(), instruction.instruction());
    }
}
