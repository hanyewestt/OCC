package com.csd.occ.planning.diagnosis.domain;

import com.csd.occ.planning.recommendation.domain.ControllerBusInstruction;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record EarlyBunchingControllerSuggestion(UUID requestId, List<ControllerBusInstruction> instructions,
                                                 String controllerRationale, String actorSubject, Instant suggestedAt) {
    public EarlyBunchingControllerSuggestion {
        Objects.requireNonNull(requestId, "requestId is required");
        instructions = List.copyOf(instructions);
        if (instructions.isEmpty() || instructions.size() > 100) {
            throw new IllegalArgumentException("instructions must contain 1..100 buses");
        }
        controllerRationale = required(controllerRationale, 1000, "controllerRationale");
        actorSubject = required(actorSubject, 255, "actorSubject");
        Objects.requireNonNull(suggestedAt, "suggestedAt is required");
    }

    private static String required(String value, int maximum, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        String trimmed = value.trim();
        if (trimmed.length() > maximum) throw new IllegalArgumentException(field + " is too long");
        return trimmed;
    }
}
