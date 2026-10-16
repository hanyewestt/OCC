package com.csd.occ.planning.recommendation.domain;

import java.util.Objects;
import java.util.Optional;

public record ModelTokenUsage(
        Optional<Integer> promptTokens,
        Optional<Integer> completionTokens,
        Optional<Integer> totalTokens) {

    public ModelTokenUsage {
        promptTokens = requireNonNegative(promptTokens, "promptTokens");
        completionTokens = requireNonNegative(completionTokens, "completionTokens");
        totalTokens = requireNonNegative(totalTokens, "totalTokens");
        if (promptTokens.isEmpty() && completionTokens.isEmpty() && totalTokens.isEmpty()) {
            throw new IllegalArgumentException("at least one token usage value is required");
        }
    }

    private static Optional<Integer> requireNonNegative(Optional<Integer> value, String name) {
        Objects.requireNonNull(value, name + " is required");
        value.ifPresent(tokens -> {
            if (tokens < 0) {
                throw new IllegalArgumentException(name + " must not be negative");
            }
        });
        return value;
    }
}
