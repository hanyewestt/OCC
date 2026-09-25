package com.csd.occ.ingestion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import org.locationtech.jts.geom.Point;

@Entity
@Table(name = "bus_stop", schema = "ingestion")
public class BusStop {
    protected BusStop() {
    }

    public BusStop(BusStopId id, String roadName, String description, Point location, Instant importedAt) {
        this.id = id;
        this.roadName = roadName;
        this.description = description;
        this.location = location;
        this.importedAt = importedAt;
    }

    @EmbeddedId
    private BusStopId id;
    private String roadName;
    private String description;
    @Column(columnDefinition = "geometry(Point,4326)")
    private Point location;
    private Instant importedAt;

    public BusStopId id() { return id; }
    public String roadName() { return roadName; }
    public String description() { return description; }
    public Point location() { return location; }
}
