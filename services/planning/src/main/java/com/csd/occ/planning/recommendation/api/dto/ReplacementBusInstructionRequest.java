package com.csd.occ.planning.recommendation.api.dto;

import com.csd.occ.planning.recommendation.domain.CandidateMitigationType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReplacementBusInstructionRequest(
        @NotBlank @Size(max = 80) String busReference,
        @NotNull CandidateMitigationType action,
        @NotNull @Min(1) @Max(120) Integer adjustmentSeconds) {}
