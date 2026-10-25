package com.csd.occ.planning.advisory.domain;

import jakarta.persistence.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name = "advisory_proposal", schema = "planning")
public class AdvisoryProposal {
    @Id private UUID id;
    private UUID eventId;
    private UUID disruptionId;
    private UUID demoRunId;
    private String busId;
    private String status;
    @Column(columnDefinition = "text") private String preview;
    @JdbcTypeCode(SqlTypes.JSON) private AdvisoryContextPort.Hazard context;
    @JdbcTypeCode(SqlTypes.JSON) private AdvisoryContextPort.Bus bus;
    @JdbcTypeCode(SqlTypes.JSON) private AdvisoryCandidate candidate;
    @JdbcTypeCode(SqlTypes.JSON) private AdvisoryCandidate finalCandidate;
    private String model;
    @Column(columnDefinition = "text") private String modelInput;
    @Column(columnDefinition = "text") private String modelOutput;
    private String controllerSubject;
    private String controllerRationale;
    private Instant createdAt;
    private Instant decidedAt;
    @Version private Long version;
    private UUID replacedInstructionId;
    protected AdvisoryProposal() {}
    public AdvisoryProposal(UUID eventId, UUID runId, AdvisoryContextPort.Hazard context, AdvisoryContextPort.Bus bus,
            AdvisoryDraftPort.Result draft, String preview, Instant now) {
        id = UUID.nameUUIDFromBytes((eventId + ":" + runId + ":" + bus.busId() + ":" + draft.candidate().type()).getBytes(StandardCharsets.UTF_8));
        this.eventId = eventId; disruptionId = context.disruptionId(); demoRunId = runId; busId = bus.busId();
        this.context = context; this.bus = bus; candidate = draft.candidate(); model = draft.model();
        modelInput = draft.input(); modelOutput = draft.output(); createdAt = now; status = "PENDING";
        this.preview = preview;
    }
    public void decide(AdvisoryCandidate chosen, String subject, String rationale, UUID replacedInstructionId, Instant now) {
        finalCandidate = chosen; controllerSubject = subject; controllerRationale = rationale; decidedAt = now; status = "APPROVED";
        this.replacedInstructionId = replacedInstructionId;
    }
    public UUID id() { return id; }
    public UUID eventId() { return eventId; }
    public UUID disruptionId() { return disruptionId; }
    public UUID demoRunId() { return demoRunId; }
    public String busId() { return busId; }
    public String status() { return status; }
    public String preview() { return preview; }
    public AdvisoryContextPort.Hazard context() { return context; }
    public AdvisoryContextPort.Bus bus() { return bus; }
    public AdvisoryCandidate candidate() { return candidate; }
    public AdvisoryCandidate finalCandidate() { return finalCandidate; }
    public String controllerSubject() { return controllerSubject; }
    public String controllerRationale() { return controllerRationale; }
    public UUID replacedInstructionId() { return replacedInstructionId; }
    public Instant createdAt() { return createdAt; }
    public Instant decidedAt() { return decidedAt; }
}
