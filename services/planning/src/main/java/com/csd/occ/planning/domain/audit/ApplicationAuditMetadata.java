package com.csd.occ.planning.domain.audit;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity @Table(name = "application_audit_metadata", schema = "planning")
public class ApplicationAuditMetadata {
    @Id private Short id;
    private Instant collectionStartedAt;
    protected ApplicationAuditMetadata() { }
    public Instant collectionStartedAt() { return collectionStartedAt; }
}
