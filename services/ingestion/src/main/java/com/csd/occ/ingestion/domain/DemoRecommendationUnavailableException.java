package com.csd.occ.ingestion.domain;

public final class DemoRecommendationUnavailableException extends RuntimeException {
    public DemoRecommendationUnavailableException() { super("Recommendation output is temporarily unavailable"); }
}
