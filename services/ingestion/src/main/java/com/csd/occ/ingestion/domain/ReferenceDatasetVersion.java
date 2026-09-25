package com.csd.occ.ingestion.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "reference_dataset_version", schema = "ingestion")
public class ReferenceDatasetVersion {
    protected ReferenceDatasetVersion() {
    }

    public ReferenceDatasetVersion(UUID id, String dataset, String versionKey, String checksum, String status,
                                   Instant importedAt) {
        this.id = id;
        this.dataset = dataset;
        this.versionKey = versionKey;
        this.checksum = checksum;
        this.status = status;
        this.importedAt = importedAt;
    }

    @Id
    private UUID id;
    private String dataset;
    private String versionKey;
    @Column(columnDefinition = "char(64)", length = 64, nullable = false)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String checksum;
    private String status;
    private Instant importedAt;
    private Instant activatedAt;

    public UUID id() { return id; }
    public String checksum() { return checksum; }
    public String status() { return status; }
    public void supersede() { status = "SUPERSEDED"; }
    public void activate(Instant activatedAt) { status = "ACTIVE"; this.activatedAt = activatedAt; }
}
