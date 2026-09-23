package com.csd.occ.planning.recommendationengine.domain;

import java.util.Objects;

public record BunchingMitigationInputs(
        ValidatedMitigationContext context,
        SimulatedImpactEstimate impact) {

    public BunchingMitigationInputs {
        Objects.requireNonNull(context, "context is required");
        Objects.requireNonNull(impact, "impact is required");
    }
}
