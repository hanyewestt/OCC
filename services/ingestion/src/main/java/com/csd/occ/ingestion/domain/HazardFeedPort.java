package com.csd.occ.ingestion.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public interface HazardFeedPort {
    Batch fetch(HazardKind kind);

    record Batch(int httpStatus, List<String> rawPages, List<Fact> facts, List<String> errors) {
        public Batch {
            rawPages = List.copyOf(rawPages); facts = List.copyOf(facts); errors = List.copyOf(errors);
        }
    }
    record Fact(HazardKind kind, String sourceEventKey, String sourceAction, Instant reportedAt,
                Instant effectiveFrom, Instant effectiveUntil, String title, String description,
                String severity, String instruction, List<String> references, String line, String direction,
                String serviceStatus, String department, List<HazardLocation> locations) {
        public Fact {
            Objects.requireNonNull(kind); Objects.requireNonNull(sourceAction); Objects.requireNonNull(title);
            if (sourceEventKey == null || sourceEventKey.isBlank()) throw new IllegalArgumentException("Source identity required");
            if (effectiveFrom != null && effectiveUntil != null && effectiveUntil.isBefore(effectiveFrom))
                throw new IllegalArgumentException("Effective interval is reversed");
            references = List.copyOf(references); locations = List.copyOf(locations);
        }
    }
}
