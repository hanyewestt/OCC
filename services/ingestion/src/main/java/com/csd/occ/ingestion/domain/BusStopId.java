package com.csd.occ.ingestion.domain;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;

@Embeddable
public record BusStopId(UUID referenceVersionId, String stopCode) implements Serializable {
}
