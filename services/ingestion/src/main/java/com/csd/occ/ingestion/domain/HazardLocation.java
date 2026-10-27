package com.csd.occ.ingestion.domain;

import java.math.BigDecimal;
import java.util.Objects;

/** Geometry is optional: road names and unresolved station references are valid source locations. */
public record HazardLocation(String type, String sourceReference, String description, BigDecimal latitude,
                             BigDecimal longitude, BigDecimal radiusKm, String geometryRole, String provenance) {
    public HazardLocation {
        Objects.requireNonNull(type); Objects.requireNonNull(geometryRole); Objects.requireNonNull(provenance);
        if ((latitude == null) != (longitude == null)) throw new IllegalArgumentException("Coordinates must be paired");
        if (latitude != null && (latitude.abs().compareTo(BigDecimal.valueOf(90)) > 0
                || longitude.abs().compareTo(BigDecimal.valueOf(180)) > 0))
            throw new IllegalArgumentException("Invalid location coordinates");
        if (radiusKm != null && radiusKm.signum() < 0) throw new IllegalArgumentException("Invalid location radius");
    }
}
