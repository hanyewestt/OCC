package com.csd.occ.ingestion.domain;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "bus_service", schema = "ingestion")
public class BusService {
    protected BusService() {
    }

    public BusService(BusServiceId id, String category, Instant importedAt) {
        this.id = id;
        this.category = category;
        this.importedAt = importedAt;
    }

    @EmbeddedId
    private BusServiceId id;
    private String category;
    private Instant importedAt;

    public BusServiceId id() { return id; }
    public String category() { return category; }
}
