package com.csd.occ.ingestion.api.dto;
import java.util.List;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Status and detected simulation alerts for one demo run")
public record DemoRunResponse(UUID id, String status, int currentStep, String serviceId, int directionId, int busCount,
                              long dispatchGapSeconds, List<Long> busGapsSeconds, String monitoredStopCode, String scenario, List<DemoBunchingAlertResponse> alerts) {
    public DemoRunResponse { busGapsSeconds = List.copyOf(busGapsSeconds); alerts = List.copyOf(alerts); }
}
