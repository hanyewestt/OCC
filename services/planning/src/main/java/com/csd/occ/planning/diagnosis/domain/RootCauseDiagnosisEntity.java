package com.csd.occ.planning.diagnosis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "diagnosis_result", schema = "planning")
public class RootCauseDiagnosisEntity {
    protected RootCauseDiagnosisEntity() { }

    public RootCauseDiagnosisEntity(RootCauseDiagnosis diagnosis, String evidenceIdsJson, String limitationsJson,
                                    String componentsJson) {
        id = diagnosis.id(); requestId = diagnosis.requestId(); causeCode = diagnosis.causeCode();
        causeSubtype = diagnosis.causeSubtype(); likelyCause = diagnosis.likelyCause();
        confidenceScoresAvailable = diagnosis.confidence() != null && diagnosis.evidenceSupportScore() != null
                && componentsJson != null;
        confidence = java.math.BigDecimal.valueOf(confidenceScoresAvailable ? diagnosis.confidence() : 0);
        evidenceSupportScore = java.math.BigDecimal.valueOf(
                confidenceScoresAvailable ? diagnosis.evidenceSupportScore() : 0);
        supportingEvidenceIds = evidenceIdsJson;
        limitations = limitationsJson; confidenceComponents = componentsJson == null ? "{}" : componentsJson;
        evidenceSnapshot = diagnosis.evidenceSnapshot(); evidenceSnapshotHash = diagnosis.evidenceSnapshotHash();
        provider = diagnosis.provider(); model = diagnosis.model(); promptVersion = diagnosis.promptVersion();
        schemaVersion = diagnosis.schemaVersion(); completedAt = diagnosis.completedAt();
    }

    @Id private UUID id;
    private UUID requestId;
    private String causeCode;
    private String causeSubtype;
    private String likelyCause;
    @Column(name = "confidence_scores_available", nullable = false)
    private boolean confidenceScoresAvailable = true;
    private java.math.BigDecimal confidence;
    private java.math.BigDecimal evidenceSupportScore;
    @JdbcTypeCode(SqlTypes.JSON) private String supportingEvidenceIds;
    @JdbcTypeCode(SqlTypes.JSON) private String limitations;
    @JdbcTypeCode(SqlTypes.JSON) private String confidenceComponents;
    @JdbcTypeCode(SqlTypes.JSON) private String evidenceSnapshot;
    private String evidenceSnapshotHash;
    private String provider;
    private String model;
    private String promptVersion;
    private String schemaVersion;
    private Instant completedAt;

    public String evidenceSnapshotJson() { return evidenceSnapshot; }
    public UUID id() { return id; }
    public UUID requestId() { return requestId; }
    public String causeCode() { return causeCode; }
    public String causeSubtype() { return causeSubtype; }
    public String likelyCause() { return likelyCause; }
    public Double confidence() { return confidenceScoresAvailable ? confidence.doubleValue() : null; }
    public Double evidenceSupportScore() { return confidenceScoresAvailable ? evidenceSupportScore.doubleValue() : null; }
    public String supportingEvidenceIdsJson() { return supportingEvidenceIds; }
    public String limitationsJson() { return limitations; }
    public Instant completedAt() { return completedAt; }
}
