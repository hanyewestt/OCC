package com.csd.occ.planning.recommendation.domain;

public interface MitigationCandidateGeneratorPort {

    MitigationCandidateGenerationResult generate(MitigationCandidateRequest request);
}
