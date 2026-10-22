package com.csd.occ.ingestion.domain;

import java.time.Duration;

/** Operational pacing settings supplied by deployment configuration. */
public interface PacingConfigurationPort {
    Duration targetHeadway();
    double tooCloseRatio();
    double tooFarRatio();
    Duration staleAfter();
    Duration streamTimeout();
    int maxSubscribers();
}
