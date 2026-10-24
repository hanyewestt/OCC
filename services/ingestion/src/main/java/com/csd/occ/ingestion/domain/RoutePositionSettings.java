package com.csd.occ.ingestion.domain;

import java.time.Duration;

public interface RoutePositionSettings {
    Duration pollInterval();
    int maxPollAgeCycles();
    double clusterToleranceMeters();
    double speedCapMetersPerSecond();
}
