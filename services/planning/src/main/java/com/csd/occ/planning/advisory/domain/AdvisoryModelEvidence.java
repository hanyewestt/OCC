package com.csd.occ.planning.advisory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Long-lived AI and controller decision evidence, independent from operational proposals. */
@Entity
@Table(name = "advisory_model_evidence", schema = "planning")
public class AdvisoryModelEvidence {
    protected AdvisoryModelEvidence() {}

    public AdvisoryModelEvidence(AdvisoryProposal proposal) {
        proposalId = proposal.id();
        eventId = proposal.eventId();
        disruptionId = proposal.disruptionId();
        demoRunId = proposal.demoRunId();
        busId = proposal.busId();
        sourceRevisionKey = proposal.sourceRevisionKey();
        candidateType = proposal.candidateType();
        model = proposal.model();
        modelInput = proposal.modelInput();
        modelOutput = proposal.modelOutput();
        candidate = proposal.candidate();
        createdAt = proposal.createdAt();
        if (proposal.decidedAt() != null)
            decide(proposal.finalCandidate(), proposal.controllerSubject(), proposal.controllerRationale(),
                    proposal.replacedInstructionId(), proposal.decidedAt());
    }

    @Id private UUID proposalId;
    private UUID eventId;
    private UUID disruptionId;
    private UUID demoRunId;
    private String busId;
    @Column(name = "source_revision_key", nullable = false, length = 100)
    private String sourceRevisionKey;
    @Column(name = "candidate_type", nullable = false, length = 30)
    private String candidateType;
    private String model;
    @Column(name = "model_input", columnDefinition = "text", nullable = false)
    private String modelInput;
    @Column(name = "model_output", columnDefinition = "text", nullable = false)
    private String modelOutput;
    @JdbcTypeCode(SqlTypes.JSON) private AdvisoryCandidate candidate;
    @JdbcTypeCode(SqlTypes.JSON) private AdvisoryCandidate finalCandidate;
    private String controllerSubject;
    @Column(columnDefinition = "text") private String controllerRationale;
    private UUID replacedInstructionId;
    private Instant createdAt;
    private Instant decidedAt;

    public void decide(AdvisoryCandidate chosen, String subject, String rationale, UUID replacementId, Instant at) {
        finalCandidate = chosen;
        controllerSubject = subject;
        controllerRationale = rationale;
        replacedInstructionId = replacementId;
        decidedAt = at;
    }

    public UUID proposalId() { return proposalId; }
    public String model() { return model; }
    public String modelInput() { return modelInput; }
    public String modelOutput() { return modelOutput; }
    public AdvisoryCandidate candidate() { return candidate; }
    public AdvisoryCandidate finalCandidate() { return finalCandidate; }
    public String controllerSubject() { return controllerSubject; }
    public String controllerRationale() { return controllerRationale; }
    public UUID replacedInstructionId() { return replacedInstructionId; }
    public Instant createdAt() { return createdAt; }
    public Instant decidedAt() { return decidedAt; }
}
