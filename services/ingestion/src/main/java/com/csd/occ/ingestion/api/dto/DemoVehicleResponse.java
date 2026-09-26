package com.csd.occ.ingestion.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Fictional vehicle identity used only in a simulation run")
public record DemoVehicleResponse(String registrationNumber, String operator, String vehicleType, String captainCode) {}
