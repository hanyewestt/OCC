package com.csd.occ.ingestion.api.dto;

import jakarta.validation.constraints.NotNull;

public record DemoModeRequest(@NotNull Boolean enabled) {}
