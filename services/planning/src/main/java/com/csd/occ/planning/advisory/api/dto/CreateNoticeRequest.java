package com.csd.occ.planning.advisory.api.dto;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CreateNoticeRequest(@NotNull UUID id, @NotNull UUID disruptionId,
        @NotBlank @Size(max = 160) String title, @NotBlank @Size(max = 2000) String message,
        @NotNull @Size(max = 100) List<@NotBlank @Size(max = 250) String> services,
        @NotNull @Size(max = 100) List<@NotBlank @Size(max = 250) String> locations,
        @NotNull Instant effectiveFrom, @NotNull Instant effectiveUntil) {}
