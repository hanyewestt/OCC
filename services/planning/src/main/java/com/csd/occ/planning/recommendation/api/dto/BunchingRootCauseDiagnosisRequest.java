package com.csd.occ.planning.recommendation.api.dto;

import com.csd.occ.planning.recommendation.domain.BunchingRootCauseDiagnosis;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Schema(description = "The root-cause diagnosis generated for the confirmed bunching event")
public record BunchingRootCauseDiagnosisRequest(
        @NotNull UUID diagnosisId,
        @NotBlank @Size(max = 1_000) String likelyCause,
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal confidence,
        @NotEmpty @Size(max = 100) List<@NotBlank @Size(max = 200) String> evidenceIds,
        @NotNull @Size(max = 20) List<@NotBlank @Size(max = 500) String> limitations) {

    public BunchingRootCauseDiagnosis toDomain() {
        return new BunchingRootCauseDiagnosis(
                diagnosisId,
                likelyCause,
                confidence,
                evidenceIds,
                limitations);
    }
}
