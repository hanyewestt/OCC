package com.csd.occ.bunching.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "ingestion_feed_cursor", schema = "bunching")
public class IngestionFeedCursor {
    protected IngestionFeedCursor() {
    }

    @Id
    private Integer id;
    private long lastSequence;

    public long lastSequence() { return lastSequence; }
    public void advanceTo(long sequence) {
        if (sequence > lastSequence) lastSequence = sequence;
    }
}
