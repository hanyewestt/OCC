package com.csd.occ.ingestion.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.UUID;

@Schema(description = "Conditional assignment command from the authenticated Planning peer")
public record DemoAssignmentOperationRequest(
        @JsonProperty(value = "captainUserId", required = true) @Schema(nullable = true) UUID captainUserId,
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotBlank @Size(max = 255) String actorSubject) { }
