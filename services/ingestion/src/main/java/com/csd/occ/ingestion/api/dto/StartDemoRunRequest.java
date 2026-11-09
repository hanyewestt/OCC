package com.csd.occ.ingestion.api.dto;

import com.csd.occ.ingestion.domain.DemoRunConfiguration;
import com.csd.occ.ingestion.domain.DemoScenario;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

@Schema(description = "Configuration for one fictional single-route simulation run")
public record StartDemoRunRequest(
        @NotBlank @Size(max = 20) @Schema(example = "190") String serviceId,
        @Min(1) @Max(99) @Schema(example = "1") Integer directionId,
        @Min(2) @Max(3) @Schema(description = "Number of buses injected into the one localised bunching event", example = "3") Integer busCount,
        @Min(15) @Max(900) @Schema(description = "Seconds between successive simulated dispatches", example = "90") Integer dispatchGapSeconds,
        @Schema(description = "Optional gap in seconds after each bus. Supply one value for each adjacent bus pair; omitted values use dispatchGapSeconds.", example = "[120, 90]") List<@Min(15) @Max(900) Integer> busGapsSeconds,
        @NotBlank @Size(max = 20) @Schema(description = "The single route stop at which this bunching injection is created", example = "44251") String monitoredStopCode,
        @NotNull @Schema(example = "BUNCHING") DemoScenario scenario,
        @Schema(description = "One distinct Supabase captain user ID per bus. Omit to use legacy demo assignment settings.") List<UUID> captainUserIds) {

    @JsonIgnore
    public boolean isEmpty() {
        return (serviceId == null || serviceId.isBlank()) && directionId == null && busCount == null && dispatchGapSeconds == null
                && (busGapsSeconds == null || busGapsSeconds.isEmpty()) && (monitoredStopCode == null || monitoredStopCode.isBlank())
                && scenario == null && (captainUserIds == null || captainUserIds.isEmpty());
    }

    public DemoRunConfiguration configuration() {
        int count = busCount != null ? busCount : 3;
        java.time.Duration dispatchGap = java.time.Duration.ofSeconds(dispatchGapSeconds != null ? dispatchGapSeconds : 90);
        return new DemoRunConfiguration(serviceId != null ? serviceId : "190",
                directionId != null ? directionId : 1, count, dispatchGap,
                busGapsSeconds == null ? java.util.stream.IntStream.range(1, count).mapToObj(ignored -> dispatchGap).toList()
                        : busGapsSeconds.stream().map(java.time.Duration::ofSeconds).toList(),
                monitoredStopCode != null ? monitoredStopCode : "44251",
                scenario != null ? scenario : DemoScenario.BUNCHING,
                captainUserIds == null ? List.of() : captainUserIds);
    }
}
