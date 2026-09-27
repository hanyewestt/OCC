package com.csd.occ.planning.recommendationengine.api.error;

public class NoActionableRecommendationException extends RuntimeException {

    public NoActionableRecommendationException(String message) {
        super(message);
    }
}
