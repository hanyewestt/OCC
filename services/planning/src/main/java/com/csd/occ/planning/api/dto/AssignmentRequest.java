package com.csd.occ.planning.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record AssignmentRequest(@JsonProperty(value = "captainUserId", required = true) UUID captainUserId,
        @NotNull @Min(0) Long expectedVersion) { }
