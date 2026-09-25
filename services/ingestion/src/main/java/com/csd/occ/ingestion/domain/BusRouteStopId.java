package com.csd.occ.ingestion.domain;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;

@Embeddable
public record BusRouteStopId(UUID referenceVersionId, String serviceId, String operator, short directionId,
                             int stopSequence) implements Serializable {
}
