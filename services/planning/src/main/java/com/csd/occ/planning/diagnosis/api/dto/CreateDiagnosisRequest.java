package com.csd.occ.planning.diagnosis.api.dto;

import com.csd.occ.planning.diagnosis.domain.EventType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

@Schema(description = "Reference to a producer-owned event. Facts and classification are retrieved from the producer.")
public record CreateDiagnosisRequest(
        @Schema(description = "Stable event category; BUS_BUNCHING is the first supported type.", example = "BUS_BUNCHING")
        @NotNull EventType eventType,
        @Schema(description = "Producer-owned event UUID; WQAP-3 retrieves authoritative facts.",
                example = "c7f94b1c-9a9d-4c22-a011-700123002001")
        @NotNull UUID eventId,
        @Schema(description = "Service that owns the event facts.", example = "disruption")
        @NotBlank String producer) { }
