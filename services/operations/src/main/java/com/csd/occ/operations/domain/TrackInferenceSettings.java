package com.csd.occ.operations.domain;

import java.time.Duration;

public record TrackInferenceSettings(Duration staleAfter, Duration expiry, Duration retention,
        Duration clusterTimeWindow, double clusterMeters, double maxRouteOffsetMeters,
        double maxSpeedMetersPerSecond, double backwardToleranceMeters,
        double ambiguityMarginMeters, Duration etaTolerance, int pageSize, int maxPages) { }
