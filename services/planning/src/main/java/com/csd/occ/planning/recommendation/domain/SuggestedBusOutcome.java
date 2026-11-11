package com.csd.occ.planning.recommendation.domain;

/** An actionable suggestion has a mitigation type; a no-instruction suggestion has a reason. */
public record SuggestedBusOutcome(String busReference, MitigationType mitigationType, int adjustmentSeconds, String reason) {}
