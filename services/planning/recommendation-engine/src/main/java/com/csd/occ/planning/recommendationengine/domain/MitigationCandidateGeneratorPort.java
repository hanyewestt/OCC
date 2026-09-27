package com.csd.occ.planning.recommendationengine.domain;

public interface MitigationCandidateGeneratorPort {

    MitigationCandidateGenerationResult generate(MitigationCandidateRequest request);
}
