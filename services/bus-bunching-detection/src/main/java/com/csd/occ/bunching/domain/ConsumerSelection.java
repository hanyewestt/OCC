package com.csd.occ.bunching.domain;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
@Entity
@Table(name = "consumer_selection", schema = "bunching")
public class ConsumerSelection {
    protected ConsumerSelection() {}
    @Id private Integer id;
    private String mode;
    private UUID demoRunId;
    private Instant changedAt;
    @Version private long version;
    public String mode() { return mode; }
    public UUID demoRunId() { return demoRunId; }
    public void select(String mode, UUID demoRunId, Instant now) { this.mode = mode; this.demoRunId = demoRunId; changedAt = now; }
}
