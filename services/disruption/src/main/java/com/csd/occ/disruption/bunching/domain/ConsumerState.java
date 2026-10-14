package com.csd.occ.disruption.bunching.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "consumer_state", schema = "disruption")
public class ConsumerState {
    protected ConsumerState() { }
    @Id private int id;
    private String mode;
    private UUID demoRunId;
    private long lastSequence;
    private Instant updatedAt;
    @Version private long version;
    public String mode() { return mode; }
    public UUID demoRunId() { return demoRunId; }
    public long lastSequence() { return lastSequence; }
    public boolean matches(String candidateMode, UUID candidateRun) { return mode.equals(candidateMode) && java.util.Objects.equals(demoRunId, candidateRun); }
    public void selectLive(long head, Instant now) { if (!matches("LIVE", null)) lastSequence = head; mode = "LIVE"; demoRunId = null; updatedAt = now; }
    public void selectDemo(UUID runId, Instant now) { if (!matches("DEMO", runId)) lastSequence = 0; mode = "DEMO"; demoRunId = runId; updatedAt = now; }
    public void advanceTo(long sequence) { if (sequence > lastSequence) lastSequence = sequence; }
}
