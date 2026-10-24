package com.csd.occ.planning.recommendation.api.dto;

import com.csd.occ.planning.recommendation.domain.ControllerBusInstruction;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Controller-authored instruction for one affected bus")
public record ControllerBusInstructionRequest(
        @NotBlank @Size(max = 80) String busReference,
        @NotBlank @Size(max = 500) String instruction) {

    public ControllerBusInstruction toDomain() {
        return new ControllerBusInstruction(busReference, instruction);
    }
}
