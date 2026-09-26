package com.csd.occ.operations.infrastructure.config;

import com.csd.occ.operations.domain.TrackInferenceSettings;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("occ.track-inference")
public record TrackInferenceProperties(boolean enabled, Duration interval, Duration initialDelay,
        Duration staleAfter, Duration expiry, Duration retention, Duration clusterTimeWindow,
        double clusterMeters, double maxRouteOffsetMeters, double maxSpeedMetersPerSecond,
        double backwardToleranceMeters, double ambiguityMarginMeters, Duration etaTolerance,
        int pageSize, int maxPages) {
    public TrackInferenceProperties {
        if (interval == null || interval.toMillis()<1000 || initialDelay == null || initialDelay.isNegative()
                || staleAfter == null || staleAfter.isNegative() || staleAfter.isZero()
                || expiry == null || expiry.compareTo(staleAfter)<0 || retention == null || retention.compareTo(expiry)<=0
                || clusterTimeWindow == null || clusterTimeWindow.isNegative() || etaTolerance == null || etaTolerance.isNegative()
                || !Double.isFinite(clusterMeters) || clusterMeters<=0 || !Double.isFinite(maxRouteOffsetMeters) || maxRouteOffsetMeters<=0
                || !Double.isFinite(maxSpeedMetersPerSecond) || maxSpeedMetersPerSecond<=0
                || !Double.isFinite(backwardToleranceMeters) || backwardToleranceMeters<0
                || !Double.isFinite(ambiguityMarginMeters) || ambiguityMarginMeters<0 || pageSize<1 || pageSize>200 || maxPages<1)
            throw new IllegalArgumentException("Invalid track inference limits");
    }
    public TrackInferenceSettings settings() {
        return new TrackInferenceSettings(staleAfter, expiry, retention, clusterTimeWindow, clusterMeters,
                maxRouteOffsetMeters, maxSpeedMetersPerSecond, backwardToleranceMeters, ambiguityMarginMeters,
                etaTolerance, pageSize, maxPages);
    }
}
