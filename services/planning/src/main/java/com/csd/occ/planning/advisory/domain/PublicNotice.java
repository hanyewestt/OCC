package com.csd.occ.planning.advisory.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name = "public_notice", schema = "planning")
public class PublicNotice {
    @Id private UUID id;
    private UUID disruptionId;
    private String classification;
    private UUID demoRunId;
    private String title;
    @Column(columnDefinition = "text") private String message;
    @JdbcTypeCode(SqlTypes.JSON) private List<String> services;
    @JdbcTypeCode(SqlTypes.JSON) private List<String> locations;
    @JdbcTypeCode(SqlTypes.JSON) private HazardEventFacts.Fact sourceEvidence;
    private Instant effectiveFrom;
    private Instant effectiveUntil;
    private String status;
    private String createdBy;
    private String publishedBy;
    private Instant createdAt;
    private Instant publishedAt;
    private Instant withdrawnAt;
    private UUID replacementId;
    @Version private Long version;
    protected PublicNotice() {}
    public PublicNotice(UUID id, AdvisoryContextPort.Hazard hazard, String title, String message,
            List<String> services, List<String> locations, Instant from, Instant until, String actor, Instant now) {
        this.id = id; disruptionId = hazard.disruptionId(); classification = hazard.classification(); demoRunId = hazard.demoRunId();
        this.title = title; this.message = message; this.services = List.copyOf(services); this.locations = List.copyOf(locations);
        sourceEvidence = hazard.facts(); effectiveFrom = from; effectiveUntil = until; createdBy = actor; createdAt = now; status = "DRAFT";
    }
    public void publish(String actor, Instant now) {
        if ("PUBLISHED".equals(status)) return;
        if (!"DRAFT".equals(status) || !now.isBefore(effectiveUntil)) throw new AdvisoryConflictException("Notice is withdrawn or expired");
        status = "PUBLISHED"; publishedBy = actor; publishedAt = now;
    }
    public void withdraw(Instant now) { if (!"WITHDRAWN".equals(status)) { status = "WITHDRAWN"; withdrawnAt = now; } }
    public void replace(UUID replacementId, Instant now) { withdraw(now); this.replacementId = replacementId; }
    public UUID id() { return id; }
    public UUID disruptionId() { return disruptionId; }
    public String classification() { return classification; }
    public UUID demoRunId() { return demoRunId; }
    public String title() { return title; }
    public String message() { return message; }
    public List<String> services() { return services; }
    public List<String> locations() { return locations; }
    public Instant effectiveFrom() { return effectiveFrom; }
    public Instant effectiveUntil() { return effectiveUntil; }
    public String status() { return status; }
    public Instant publishedAt() { return publishedAt; }
    public Instant withdrawnAt() { return withdrawnAt; }
    public UUID replacementId() { return replacementId; }
}
