package com.csd.occ.operations.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateDemoRunRequest(@NotBlank @Size(max = 20) String serviceId,
        @Min(1) int directionId) { }
