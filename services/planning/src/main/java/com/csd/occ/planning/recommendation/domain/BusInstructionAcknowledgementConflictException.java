package com.csd.occ.planning.recommendation.domain;

import java.util.UUID;

public class BusInstructionAcknowledgementConflictException extends RuntimeException {
    public BusInstructionAcknowledgementConflictException(UUID instructionId) {
        super("Bus instruction was acknowledged by another actor: " + instructionId);
    }
}
