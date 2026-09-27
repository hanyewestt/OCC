package com.csd.occ.bunching.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "ingestion_feed_cursor", schema = "bunching")
public class IngestionFeedCursor {
    protected IngestionFeedCursor() {
    }

    @Id
    private Integer id;
    private long lastSequence;
    private String feedScope = "LIVE";
    private UUID demoRunId;

    public long lastSequence() { return lastSequence; }
    public String feedScope() { return feedScope; }
    public UUID demoRunId() { return demoRunId; }
    public boolean matches(String scope, UUID runId) {
        return scope.equals(feedScope) && java.util.Objects.equals(demoRunId, runId);
    }
    public void selectDemo(UUID runId) {
        String nextScope = "DEMO:" + runId;
        if (!nextScope.equals(feedScope) || !runId.equals(demoRunId)) lastSequence = 0;
        feedScope = nextScope;
        demoRunId = runId;
    }
    public void selectLive(long headSequence) {
        feedScope = "LIVE";
        demoRunId = null;
        lastSequence = headSequence;
    }
    public void advanceTo(long sequence) {
        if (sequence > lastSequence) lastSequence = sequence;
    }
}
