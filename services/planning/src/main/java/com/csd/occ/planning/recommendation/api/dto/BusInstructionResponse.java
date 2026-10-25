package com.csd.occ.planning.recommendation.api.dto;

import com.csd.occ.planning.recommendation.domain.BusInstruction;
import com.csd.occ.planning.recommendation.domain.BusInstructionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "One instruction published to a simulation bus for display by the mock OBU")
public record BusInstructionResponse(
        UUID instructionId,
        UUID recommendationId,
        String busReference,
        String instruction,
        BusInstructionStatus status,
        Instant sentAt,
        @Schema(nullable = true) Instant acknowledgedAt,
        @Schema(nullable = true) String acknowledgedBy) {

    public static BusInstructionResponse from(BusInstruction instruction) {
        return new BusInstructionResponse(
                instruction.instructionId(),
                instruction.recommendationId(),
                instruction.busReference(),
                instruction.instruction(),
                instruction.status(),
                instruction.sentAt(),
                instruction.acknowledgedAt().orElse(null),
                instruction.acknowledgedBy().orElse(null));
    }
}
