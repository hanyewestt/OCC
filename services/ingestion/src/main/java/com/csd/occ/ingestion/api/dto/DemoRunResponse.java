package com.csd.occ.ingestion.api.dto;
import java.util.List;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Status and detected simulation alerts for one demo run")
public record DemoRunResponse(UUID id, String status, int currentStep, List<DemoBunchingAlertResponse> alerts) {
    public DemoRunResponse { alerts = List.copyOf(alerts); }
}
