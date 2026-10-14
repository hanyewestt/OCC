package com.csd.occ.planning.recommendation.domain;

import java.util.Objects;

public sealed interface MitigationCandidateGenerationResult
        permits MitigationCandidateGenerationResult.Generated,
                MitigationCandidateGenerationResult.Empty,
                MitigationCandidateGenerationResult.Failed {

    ModelInvocationMetadata metadata();

    record Generated(
            ModelInvocationMetadata metadata,
            MitigationCandidate candidate) implements MitigationCandidateGenerationResult {

        public Generated {
            Objects.requireNonNull(metadata, "metadata is required");
            Objects.requireNonNull(candidate, "candidate is required");
        }
    }

    record Empty(ModelInvocationMetadata metadata) implements MitigationCandidateGenerationResult {

        public Empty {
            Objects.requireNonNull(metadata, "metadata is required");
        }
    }

    record Failed(
            ModelInvocationMetadata metadata,
            ModelInvocationFailureCategory failureCategory) implements MitigationCandidateGenerationResult {

        public Failed {
            Objects.requireNonNull(metadata, "metadata is required");
            Objects.requireNonNull(failureCategory, "failureCategory is required");
        }
    }
}
