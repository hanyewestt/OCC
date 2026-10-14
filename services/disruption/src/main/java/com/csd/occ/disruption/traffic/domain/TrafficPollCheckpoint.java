package com.csd.occ.disruption.traffic.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "traffic_poll_checkpoint", schema = "disruption")
public class TrafficPollCheckpoint implements Persistable<Integer> {
    protected TrafficPollCheckpoint() { }

    @Id private Integer id;
    private Instant completedAt;
    private UUID pollRunId;
    @jakarta.persistence.Version private long version;
    @jakarta.persistence.Transient private boolean fresh;

    public static TrafficPollCheckpoint initial() {
        TrafficPollCheckpoint checkpoint = new TrafficPollCheckpoint();
        checkpoint.id = 1;
        checkpoint.fresh = true;
        return checkpoint;
    }

    public void advance(Instant completedAt, UUID pollRunId) {
        this.completedAt = completedAt;
        this.pollRunId = pollRunId;
    }

    @Override public Integer getId() { return id; }
    @Override public boolean isNew() { return fresh; }
    @jakarta.persistence.PostLoad
    @jakarta.persistence.PostPersist
    void persisted() { fresh = false; }
    public Instant completedAt() { return completedAt; }
    public UUID pollRunId() { return pollRunId; }
}
