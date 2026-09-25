package com.csd.occ.ingestion.domain;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "bus_route_stop", schema = "ingestion")
public class BusRouteStop {
    protected BusRouteStop() {
    }

    public BusRouteStop(BusRouteStopId id, String stopCode, BigDecimal distanceKm, Instant importedAt) {
        this.id = id;
        this.stopCode = stopCode;
        this.distanceKm = distanceKm;
        this.importedAt = importedAt;
    }

    @EmbeddedId
    private BusRouteStopId id;
    private String stopCode;
    private BigDecimal distanceKm;
    private Instant importedAt;

    public BusRouteStopId id() { return id; }
    public String stopCode() { return stopCode; }
    public BigDecimal distanceKm() { return distanceKm; }
}
