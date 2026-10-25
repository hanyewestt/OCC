package com.csd.occ.planning.recommendation.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record BusInstruction(
        UUID instructionId,
        UUID recommendationId,
        String busReference,
        String instruction,
        BusInstructionStatus status,
        Instant sentAt,
        Optional<Instant> acknowledgedAt,
        Optional<String> acknowledgedBy) {

    public BusInstruction {
        Objects.requireNonNull(instructionId, "instructionId is required");
        Objects.requireNonNull(recommendationId, "recommendationId is required");
        busReference = requireBounded(busReference, 80, "busReference");
        instruction = requireBounded(instruction, 500, "instruction");
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(sentAt, "sentAt is required");
        Objects.requireNonNull(acknowledgedAt, "acknowledgedAt is required");
        Objects.requireNonNull(acknowledgedBy, "acknowledgedBy is required");

        if (status == BusInstructionStatus.SENT
                && (acknowledgedAt.isPresent() || acknowledgedBy.isPresent())) {
            throw new IllegalArgumentException("sent instructions cannot contain acknowledgement details");
        }
        if (status == BusInstructionStatus.ACKNOWLEDGED
                && (acknowledgedAt.isEmpty() || acknowledgedBy.isEmpty())) {
            throw new IllegalArgumentException("acknowledged instructions require acknowledgement details");
        }
        acknowledgedBy = acknowledgedBy.map(value -> requireBounded(value, 255, "acknowledgedBy"));
        acknowledgedAt.ifPresent(value -> {
            if (value.isBefore(sentAt)) {
                throw new IllegalArgumentException("acknowledgedAt cannot be before sentAt");
            }
        });
    }

    public static BusInstruction sent(UUID instructionId, UUID recommendationId, String busReference,
            String instruction, Instant sentAt) {
        return new BusInstruction(instructionId, recommendationId, busReference, instruction,
                BusInstructionStatus.SENT, sentAt, Optional.empty(), Optional.empty());
    }

    public BusInstruction acknowledge(String actorSubject, Instant at) {
        if (status == BusInstructionStatus.ACKNOWLEDGED) return this;
        return new BusInstruction(instructionId, recommendationId, busReference, instruction,
                BusInstructionStatus.ACKNOWLEDGED, sentAt, Optional.of(at), Optional.of(actorSubject));
    }

    private static String requireBounded(String value, int maximum, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maximum) {
            throw new IllegalArgumentException(fieldName + " must be at most " + maximum + " characters");
        }
        return trimmed;
    }
}
