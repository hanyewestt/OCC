package com.csd.occ.ingestion.domain;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "bus_service", schema = "ingestion")
public class BusService {
    protected BusService() {
    }

    public BusService(BusServiceId id, String category) {
        this.id = id;
        this.category = category;
    }

    @EmbeddedId
    private BusServiceId id;
    private String category;

    public BusServiceId id() { return id; }
    public String category() { return category; }
}
