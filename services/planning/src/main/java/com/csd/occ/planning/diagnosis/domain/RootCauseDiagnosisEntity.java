package com.csd.occ.planning.diagnosis.domain;

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
        confidence = diagnosis.confidence() == null ? null : java.math.BigDecimal.valueOf(diagnosis.confidence());
        evidenceSupportScore = diagnosis.evidenceSupportScore() == null ? null : java.math.BigDecimal.valueOf(diagnosis.evidenceSupportScore());
        supportingEvidenceIds = evidenceIdsJson;
        limitations = limitationsJson; confidenceComponents = componentsJson;
        evidenceSnapshot = diagnosis.evidenceSnapshot(); evidenceSnapshotHash = diagnosis.evidenceSnapshotHash();
        provider = diagnosis.provider(); model = diagnosis.model(); promptVersion = diagnosis.promptVersion();
        schemaVersion = diagnosis.schemaVersion(); completedAt = diagnosis.completedAt();
    }

    @Id private UUID id;
    private UUID requestId;
    private String causeCode;
    private String causeSubtype;
    private String likelyCause;
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
    public Double confidence() { return confidence == null ? null : confidence.doubleValue(); }
    public Double evidenceSupportScore() { return evidenceSupportScore == null ? null : evidenceSupportScore.doubleValue(); }
    public String supportingEvidenceIdsJson() { return supportingEvidenceIds; }
    public String limitationsJson() { return limitations; }
    public Instant completedAt() { return completedAt; }
}
