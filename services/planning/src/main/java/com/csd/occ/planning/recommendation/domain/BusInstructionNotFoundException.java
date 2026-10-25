package com.csd.occ.planning.recommendation.domain;

import java.util.UUID;

public class BusInstructionNotFoundException extends RuntimeException {
    public BusInstructionNotFoundException(UUID instructionId) {
        super("Bus instruction not found: " + instructionId);
    }
}
