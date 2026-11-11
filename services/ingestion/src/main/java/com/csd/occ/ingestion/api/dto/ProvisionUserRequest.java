package com.csd.occ.ingestion.api.dto;
import com.csd.occ.ingestion.domain.OccRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
public record ProvisionUserRequest(@NotBlank @Schema(description="Trimmed before validation; 1 to 200 characters after trimming") String displayName,
        @NotBlank @Schema(description="Trimmed and lowercased before validation; valid email, maximum 254 characters after trimming") String email,@NotNull @Schema(allowableValues={"CONTROLLER","CAPTAIN"}) OccRole role,
        @NotNull @Schema(minLength=8,maxLength=128,format="password",description="8 to 128 Unicode code points, preserved exactly; never retained") String password) {
    @Override public String toString() { return "ProvisionUserRequest[role="+role+", password=REDACTED]"; }
}
