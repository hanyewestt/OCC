package com.csd.occ.operations.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record PlaceDemoVehicleRequest(
        @NotBlank @Size(max = 80) String displayLabel,
        @Schema(nullable = true, description = "Optional synthetic identifier; must start with DEMO-")
        @Pattern(regexp = "DEMO-[A-Z0-9-]{1,15}") String syntheticPlate,
        @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
        @NotBlank @Size(max = 100) String driverDisplayName) { }
