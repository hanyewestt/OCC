package com.csd.occ.planning.diagnosis.api.dto;

import com.csd.occ.planning.diagnosis.domain.BunchingEventFacts;
import com.csd.occ.planning.diagnosis.domain.EventType;
import com.csd.occ.planning.diagnosis.domain.ProducerEventSubmission;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Versioned immutable event facts and evidence submitted by the owning service.")
public record CreateDiagnosisEventSubmission(
        @Schema(example = "planning-event-v1") @NotBlank String schemaVersion,
        @Schema(example = "BUS_BUNCHING") @NotNull EventType eventType,
        @Schema(example = "disruption") @NotBlank String producer,
        @Schema(example = "c7f94b1c-9a9d-4c22-a011-700123002001") @NotNull UUID eventId,
        @NotNull UUID disruptionId,
        @Schema(example = "SIMULATION") @NotBlank String classification,
        @NotNull UUID demoRunId,
        @NotNull Instant detectedAt,
        @NotBlank String detectionRuleVersion,
        @NotNull @Size(min = 1, max = 100) List<@NotBlank @Size(max = 200) String> evidenceIds,
        @NotNull @Valid BunchingEventFacts eventSnapshot) {

    public ProducerEventSubmission toDomain() {
        return new ProducerEventSubmission(schemaVersion, eventType, producer, eventId, disruptionId, classification,
                demoRunId, detectedAt, detectionRuleVersion, evidenceIds, eventSnapshot);
    }
}
