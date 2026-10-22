package com.csd.occ.ingestion.domain;

import java.time.Instant;

/** Read-only projection of one current predicted arrival used for pacing. */
public interface PacingArrivalView {
    String getClassification();
    String getStopCode();
    String getServiceId();
    Integer getDirectionId();
    Instant getEstimatedArrivalAt();
    Instant getFetchedAt();
    /** Null for live predictions: Bus Arrival does not identify vehicles. */
    String getVehicleReference();
}
