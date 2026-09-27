package com.csd.occ.ingestion.domain;

import java.math.BigDecimal;

public record DemoRecommendationImpact(int headwayBeforeSeconds, int headwayAfterSeconds,
                                       int queueBeforePassengers, int queueAfterPassengers,
                                       BigDecimal passengerMinutesBefore, BigDecimal passengerMinutesAfter) {}
