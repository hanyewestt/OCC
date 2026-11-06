package com.csd.occ.ingestion.api.dto;

import com.csd.occ.ingestion.domain.DemoRunConfiguration;
import com.csd.occ.ingestion.domain.DemoScenario;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "Configuration for one fictional single-route simulation run")
public record StartDemoRunRequest(
        @NotBlank @Size(max = 20) @Schema(example = "190") String serviceId,
        @Min(1) @Max(99) @Schema(example = "1") int directionId,
        @Min(2) @Max(3) @Schema(description = "Number of buses injected into the one localised bunching event", example = "3") int busCount,
        @Min(15) @Max(900) @Schema(description = "Seconds between successive simulated dispatches", example = "90") int dispatchGapSeconds,
        @Schema(description = "Optional gap in seconds after each bus. Supply one value for each adjacent bus pair; omitted values use dispatchGapSeconds.", example = "[120, 90]") List<@Min(15) @Max(900) Integer> busGapsSeconds,
        @NotBlank @Size(max = 20) @Schema(description = "The single route stop at which this bunching injection is created", example = "44251") String monitoredStopCode,
        @NotNull @Schema(example = "BUNCHING") DemoScenario scenario) {
    public DemoRunConfiguration configuration() {
        java.time.Duration dispatchGap = java.time.Duration.ofSeconds(dispatchGapSeconds);
        return new DemoRunConfiguration(serviceId, directionId, busCount, dispatchGap,
                busGapsSeconds == null ? java.util.stream.IntStream.range(1, busCount).mapToObj(ignored -> dispatchGap).toList()
                        : busGapsSeconds.stream().map(java.time.Duration::ofSeconds).toList(), monitoredStopCode, scenario);
    }
}
