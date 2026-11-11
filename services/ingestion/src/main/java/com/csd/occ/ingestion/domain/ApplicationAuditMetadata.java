package com.csd.occ.ingestion.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "application_audit_metadata", schema = "ingestion")
public class ApplicationAuditMetadata {
    @Id private Short id;
    private Instant collectionStartedAt;
    protected ApplicationAuditMetadata() { }
    public Instant collectionStartedAt() { return collectionStartedAt; }
}
