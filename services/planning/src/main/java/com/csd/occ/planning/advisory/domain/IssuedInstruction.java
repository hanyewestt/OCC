package com.csd.occ.planning.advisory.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name = "issued_instruction", schema = "planning")
public class IssuedInstruction {
    @Id private UUID id;
    private UUID proposalId;
    private UUID replacedInstructionId;
    private UUID disruptionId;
    private UUID demoRunId;
    private String busId;
    private UUID captainUserId;
    @Enumerated(EnumType.STRING) private InstructionType type;
    @JdbcTypeCode(SqlTypes.JSON) private InstructionParameters parameters;
    @Column(columnDefinition = "text") private String message;
    private String hazardClassification;
    private String controllerSubject;
    private String status;
    private Instant issuedAt;
    private Instant acknowledgementDeadline;
    private Instant acknowledgedAt;
    private String acknowledgementSubject;
    private Instant applicableUntil;
    private String inactiveReason;
    @Version private Long version;
    protected IssuedInstruction() {}
    public IssuedInstruction(UUID proposalId, UUID disruptionId, UUID runId, AdvisoryContextPort.Bus bus,
            AdvisoryCandidate chosen, String message, String hazardClassification, String controller, Instant now,
            java.time.Duration acknowledgementWindow) {
        this(proposalId, null, disruptionId, runId, bus, chosen, message, hazardClassification, controller, now, acknowledgementWindow);
    }
    public IssuedInstruction(UUID proposalId, UUID replacedInstructionId, UUID disruptionId, UUID runId, AdvisoryContextPort.Bus bus,
            AdvisoryCandidate chosen, String message, String hazardClassification, String controller, Instant now,
            java.time.Duration acknowledgementWindow) {
        id = proposalId; this.proposalId = proposalId; this.disruptionId = disruptionId; demoRunId = runId;
        this.replacedInstructionId = replacedInstructionId;
        busId = bus.busId(); captainUserId = java.util.Objects.requireNonNull(bus.captainUserId()); type = chosen.type();
        parameters = chosen.parameters(); this.message = message; this.hazardClassification = hazardClassification;
        controllerSubject = controller; status = "ISSUED"; issuedAt = now; acknowledgementDeadline = now.plus(acknowledgementWindow);
    }
    public void acknowledge(String subject, Instant now) {
        if (acknowledgedAt != null) return;
        if (!"ISSUED".equals(status) || !now.isBefore(acknowledgementDeadline)) throw new AdvisoryConflictException("Instruction is no longer acknowledgeable");
        acknowledgedAt = now.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        acknowledgementSubject = subject; status = "ACKNOWLEDGED";
        if (type == InstructionType.REDUCE_SPEED) applicableUntil = acknowledgedAt.plusSeconds(parameters.durationSeconds());
    }
    public void expire(String reason) { status = "EXPIRED"; inactiveReason = reason; }
    public void cancel(String reason) { status = "CANCELLED"; inactiveReason = reason; }
    public boolean isCurrent(Instant now) {
        return java.util.List.of("ISSUED", "ACKNOWLEDGED").contains(status)
                && (acknowledgedAt != null || now.isBefore(acknowledgementDeadline))
                && (applicableUntil == null || now.isBefore(applicableUntil));
    }
    public UUID id() { return id; }
    public UUID proposalId() { return proposalId; }
    public UUID replacedInstructionId() { return replacedInstructionId; }
    public UUID disruptionId() { return disruptionId; }
    public UUID demoRunId() { return demoRunId; }
    public String busId() { return busId; }
    public UUID captainUserId() { return captainUserId; }
    public InstructionType type() { return type; }
    public InstructionParameters parameters() { return parameters; }
    public String message() { return message; }
    public String hazardClassification() { return hazardClassification; }
    public String controllerSubject() { return controllerSubject; }
    public String status() { return status; }
    public Instant issuedAt() { return issuedAt; }
    public Instant acknowledgementDeadline() { return acknowledgementDeadline; }
    public Instant acknowledgedAt() { return acknowledgedAt; }
    public Instant applicableUntil() { return applicableUntil; }
    public String inactiveReason() { return inactiveReason; }
}
