package com.csd.occ.ingestion.domain;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;

@Embeddable
public record BusServiceId(UUID referenceVersionId, String serviceId, String operator) implements Serializable {
}
