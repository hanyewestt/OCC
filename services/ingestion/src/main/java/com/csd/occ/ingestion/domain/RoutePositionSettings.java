package com.csd.occ.ingestion.domain;

import java.time.Duration;

public interface RoutePositionSettings {
    Duration pollInterval();
    int maxPollAgeCycles();
    double clusterToleranceMeters();
    double speedCapMetersPerSecond();
    double uncertaintyMeters();
    double circularProjectionSpeedMetersPerSecond();
    int circularProjectionMinimumStopCount();
    double identityLinkDistanceMeters();
    double identityLinkProjectionSpeedMetersPerSecond();
    Duration identityEtaTolerance();
    boolean isIdentityLinkEnabled(String serviceId, int directionId);
    boolean isCoordinatedRefreshBoundaryEnabled(String serviceId, int directionId);
    double refreshBoundaryDistanceMeters();
    Duration refreshBoundaryWindow();
    Duration refreshBoundaryAllowedOverlap();
    Duration refreshBoundaryProfileResidual();
    double refreshBoundaryProfileMinimumSecondsPerMeter();
    double refreshBoundaryProfileMaximumSecondsPerMeter();
    double circularPhaseLinkDistanceMeters();
    double circularPhaseMaximumGapMeters();
    Duration circularPhaseMaximumEtaError();
    boolean isCircularPhaseLinkEnabled(String serviceId, int directionId);
}
