package com.csd.occ.planning.advisory.api.dto;

import com.csd.occ.planning.advisory.domain.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InstructionResponse(UUID id, UUID proposalId, UUID disruptionId, UUID demoRunId, String busId,
        InstructionType type, InstructionParameters parameters, String message, String busClassification,
        String hazardClassification, String status, Instant issuedAt, Instant acknowledgementDeadline,
        @io.swagger.v3.oas.annotations.media.Schema(nullable = true) Instant acknowledgedAt,
        @io.swagger.v3.oas.annotations.media.Schema(nullable = true) Instant applicableUntil,
        @io.swagger.v3.oas.annotations.media.Schema(nullable = true) String inactiveReason) {
    public static InstructionResponse from(IssuedInstruction value) {
        return new InstructionResponse(value.id(), value.proposalId(), value.disruptionId(), value.demoRunId(), value.busId(),
                value.type(), value.parameters(), value.message(), "SIMULATION", value.hazardClassification(), value.status(),
                value.issuedAt(), value.acknowledgementDeadline(), value.acknowledgedAt(), value.applicableUntil(), value.inactiveReason());
    }
    @io.swagger.v3.oas.annotations.media.Schema(name = "InstructionPageResponse")
    public record PageResponse(List<InstructionResponse> items, int page, int size, long totalItems, int totalPages,
                               Instant serverTime) {}
}
