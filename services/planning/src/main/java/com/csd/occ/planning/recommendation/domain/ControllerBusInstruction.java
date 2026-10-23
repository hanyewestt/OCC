package com.csd.occ.planning.recommendation.domain;

public record ControllerBusInstruction(String busReference, String instruction) {
    public ControllerBusInstruction {
        busReference = requireBounded(busReference, 80, "busReference");
        instruction = requireBounded(instruction, 500, "instruction");
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
