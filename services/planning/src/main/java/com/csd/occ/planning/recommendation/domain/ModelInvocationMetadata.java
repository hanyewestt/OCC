package com.csd.occ.planning.recommendation.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record ModelInvocationMetadata(
        UUID invocationId,
        ModelInvocationProvider provider,
        String model,
        String promptVersion,
        String outputSchemaVersion,
        Optional<String> providerResponseId,
        Instant startedAt,
        Instant completedAt,
        long latencyMilliseconds,
        Optional<ModelTokenUsage> tokenUsage, String inputSnapshot, String generatedResponse) {

    public ModelInvocationMetadata(UUID invocationId, ModelInvocationProvider provider, String model,
            String promptVersion, String outputSchemaVersion, Optional<String> providerResponseId,
            Instant startedAt, Instant completedAt, long latencyMilliseconds, Optional<ModelTokenUsage> tokenUsage) {
        this(invocationId, provider, model, promptVersion, outputSchemaVersion, providerResponseId,
                startedAt, completedAt, latencyMilliseconds, tokenUsage, null, null);
    }

    public ModelInvocationMetadata {
        Objects.requireNonNull(invocationId, "invocationId is required");
        Objects.requireNonNull(provider, "provider is required");
        model = requireText(model, "model", 128);
        promptVersion = requireText(promptVersion, "promptVersion", 64);
        outputSchemaVersion = requireText(outputSchemaVersion, "outputSchemaVersion", 64);
        Objects.requireNonNull(providerResponseId, "providerResponseId is required");
        providerResponseId = providerResponseId.map(value -> requireText(value, "providerResponseId", 255));
        Objects.requireNonNull(startedAt, "startedAt is required");
        Objects.requireNonNull(completedAt, "completedAt is required");
        if (completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("completedAt must not be before startedAt");
        }
        if (latencyMilliseconds < 0) {
            throw new IllegalArgumentException("latencyMilliseconds must not be negative");
        }
        Objects.requireNonNull(tokenUsage, "tokenUsage is required");
    }

    private static String requireText(String value, String name, int maximumLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must not exceed " + maximumLength + " characters");
        }
        return normalized;
    }
}
