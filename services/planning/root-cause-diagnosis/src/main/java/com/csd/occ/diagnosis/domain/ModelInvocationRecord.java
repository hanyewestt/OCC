package com.csd.occ.diagnosis.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "model_invocation_record", schema = "diagnosis")
public class ModelInvocationRecord {
    protected ModelInvocationRecord() { }
    public ModelInvocationRecord(UUID id, UUID requestId, ModelInvocationDetails details, String evidenceSnapshotHash) {
        this.id = id; this.requestId = requestId; provider = details.provider(); configuredModel = details.model();
        responseId = details.responseId(); promptVersion = details.promptVersion(); schemaVersion = details.schemaVersion();
        invocationType = details.attemptNumber() == 1 ? "INITIAL" : "SCHEMA_REPAIR";
        attemptNumber = details.attemptNumber(); startedAt = details.startedAt(); completedAt = details.completedAt();
        latencyMillis = details.latencyMillis(); promptTokens = details.promptTokens();
        completionTokens = details.completionTokens(); totalTokens = details.totalTokens();
        validationOutcome = details.validationOutcome(); failureCategory = details.failureCategory();
        this.evidenceSnapshotHash = evidenceSnapshotHash;
        inputSnapshot = details.inputSnapshot();
        generatedResponse = details.generatedResponse();
    }
    @Id private UUID id;
    private UUID requestId;
    private String provider;
    private String configuredModel;
    private String responseId;
    private String promptVersion;
    private String schemaVersion;
    private String evidenceSnapshotHash;
    private String invocationType;
    private int attemptNumber;
    private Instant startedAt;
    private Instant completedAt;
    private long latencyMillis;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private String validationOutcome;
    private String failureCategory;
    // Legacy attempts and provider failures may have no returned response.
    @jakarta.persistence.Column(columnDefinition = "text") private String generatedResponse;
    // Legacy attempts predate self-contained input capture.
    @jakarta.persistence.Column(columnDefinition = "text") private String inputSnapshot;
}
