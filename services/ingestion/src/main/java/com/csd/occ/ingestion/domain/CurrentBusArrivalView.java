package com.csd.occ.ingestion.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Read-only projection of the latest complete arrival snapshot for a stop. */
public interface CurrentBusArrivalView {
    UUID getId();
    UUID getRawObservationId();
    String getStopCode();
    String getServiceId();
    String getOperator();
    Integer getDirectionId();
    Integer getVisitOrder();
    Instant getEstimatedArrivalAt();
    String getOriginCode();
    String getDestinationCode();
    Boolean getMonitored();
    BigDecimal getLatitude();
    BigDecimal getLongitude();
    String getLoadCategory();
    String getFeature();
    String getVehicleType();
    Instant getFetchedAt();
    Long getAgeSeconds();
    UUID getReferenceVersionId();
}
