package com.csd.occ.diagnosis.domain;

import java.time.Duration;
import java.util.Objects;

public final class RetryBackoffPolicy {
    public Duration delay(Duration initialDelay, Duration maximumDelay, int attempt, double jitterFraction) {
        Objects.requireNonNull(initialDelay, "initialDelay is required");
        Objects.requireNonNull(maximumDelay, "maximumDelay is required");
        if (initialDelay.isNegative() || initialDelay.isZero() || maximumDelay.compareTo(initialDelay) < 0)
            throw new IllegalArgumentException("retry delays must be positive and ordered");
        if (attempt < 1) throw new IllegalArgumentException("attempt must be positive");
        if (!Double.isFinite(jitterFraction) || jitterFraction < -0.20 || jitterFraction > 0.20)
            throw new IllegalArgumentException("jitterFraction must be from -0.20 through 0.20");
        long maximumMillis = maximumDelay.toMillis();
        long baseMillis = Math.min(initialDelay.toMillis(), maximumMillis);
        for (int index = 1; index < attempt && baseMillis < maximumMillis && index < 63; index++)
            baseMillis = baseMillis > maximumMillis / 2 ? maximumMillis : Math.min(maximumMillis, baseMillis * 2);
        long jittered = Math.round(baseMillis * (1.0 + jitterFraction));
        return Duration.ofMillis(Math.min(maximumMillis, Math.max(0, jittered)));
    }
}
