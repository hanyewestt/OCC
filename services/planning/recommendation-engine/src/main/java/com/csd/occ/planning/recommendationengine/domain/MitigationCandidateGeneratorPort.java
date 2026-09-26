package com.csd.occ.planning.recommendationengine.domain;

import java.util.Optional;

public interface MitigationCandidateGeneratorPort {

    Optional<MitigationCandidate> generate(MitigationCandidateRequest request);
}
