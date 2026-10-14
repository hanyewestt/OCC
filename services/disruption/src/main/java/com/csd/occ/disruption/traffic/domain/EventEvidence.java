package com.csd.occ.disruption.traffic.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "event_evidence", schema = "disruption")
@IdClass(EventEvidenceId.class)
public class EventEvidence {
    protected EventEvidence() { }
    public EventEvidence(UUID eventId, UUID sourceId, String snapshot, Instant at) {
        this.eventId = eventId; this.evidenceKind = "TRAFFIC_INCIDENT"; this.sourceId = sourceId;
        evidenceSnapshot = snapshot; evidenceAt = at;
    }
    @Id private UUID eventId;
    @Id private String evidenceKind;
    @Id private UUID sourceId;
    @JdbcTypeCode(SqlTypes.JSON) private String evidenceSnapshot;
    private Instant evidenceAt;
}
