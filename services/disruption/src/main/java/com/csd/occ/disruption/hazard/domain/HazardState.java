package com.csd.occ.disruption.hazard.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "hazard_state", schema = "disruption")
public class HazardState {
    @Id private UUID id;
    private UUID disruptionId;
    private String source;
    private String scope;
    private String kind;
    private String sourceEventKey;
    private String classification;
    private UUID demoRunId;
    private String status;
    private String inactiveReason;
    private Instant openedAt;
    private Instant updatedAt;
    private Instant lastSeenAt;
    private Instant firstAbsentAt;
    private int absenceCount;
    private UUID sourceObservationId;
    @JdbcTypeCode(SqlTypes.JSON) private HazardPollPort.Fact facts;
    // Hibernate's JSON mapper writes Instant as epoch seconds; peer/imported JSON may use ISO text.
    @org.hibernate.annotations.Formula("case jsonb_typeof(facts->'effectiveUntil') when 'number' "
            + "then to_timestamp(cast(facts->>'effectiveUntil' as double precision)) "
            + "else cast(facts->>'effectiveUntil' as timestamptz) end")
    private Instant effectiveUntil;
    @Version private long version;
    protected HazardState() {}
    public HazardState(HazardPollPort.Poll poll, HazardPollPort.Observation observation, Instant now) {
        id = UUID.randomUUID(); disruptionId = UUID.randomUUID(); source = poll.source(); scope = poll.scope();
        kind = observation.facts().kind(); sourceEventKey = observation.facts().sourceEventKey();
        classification = poll.classification(); demoRunId = poll.demoRunId(); openedAt = now;
        observe(observation, poll.completedAt(), now);
    }
    public void observe(HazardPollPort.Observation observation, Instant seenAt, Instant now) {
        facts = observation.facts(); sourceObservationId = observation.id(); lastSeenAt = seenAt;
        absenceCount = 0; firstAbsentAt = null; updatedAt = now; inactiveReason = null;
        status = facts.effectiveFrom() != null && now.isBefore(facts.effectiveFrom()) ? "SCHEDULED" : "ACTIVE";
    }
    public void markSeen(Instant at) { lastSeenAt = at; absenceCount = 0; firstAbsentAt = null; }
    public void markAbsent(Instant at) { absenceCount++; if (firstAbsentAt == null) firstAbsentAt = at; }
    public void clear(String reason, Instant at) { status = "CLEARED"; inactiveReason = reason; updatedAt = at; }
    public void activate(Instant at) { status = "ACTIVE"; updatedAt = at; }
    public UUID id() { return id; }
    public UUID disruptionId() { return disruptionId; }
    public String source() { return source; }
    public String scope() { return scope; }
    public String kind() { return kind; }
    public String sourceEventKey() { return sourceEventKey; }
    public String classification() { return classification; }
    public UUID demoRunId() { return demoRunId; }
    public String status() { return status; }
    public String inactiveReason() { return inactiveReason; }
    public Instant openedAt() { return openedAt; }
    public Instant updatedAt() { return updatedAt; }
    public Instant lastSeenAt() { return lastSeenAt; }
    public Instant firstAbsentAt() { return firstAbsentAt; }
    public int absenceCount() { return absenceCount; }
    public UUID sourceObservationId() { return sourceObservationId; }
    public HazardPollPort.Fact facts() { return facts; }
}
