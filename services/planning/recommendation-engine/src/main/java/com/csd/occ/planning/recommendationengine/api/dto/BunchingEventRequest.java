package com.csd.occ.planning.recommendationengine.api.dto;

import com.csd.occ.planning.recommendationengine.domain.BunchingEventStatus;
import com.csd.occ.planning.recommendationengine.domain.ConfirmedBunchingEvent;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "A deterministic bus-bunching detection supplied by the detection pipeline")
public record BunchingEventRequest(
        @NotNull UUID bunchingEventId,
        @NotBlank @Size(max = 20) String serviceId,
        @Min(1) @Max(2) int directionId,
        @NotBlank @Size(max = 12) String monitoredStopCode,
        @NotNull BunchingEventStatus status,
        @Min(1) int expectedHeadwaySeconds,
        @NotEmpty @Size(min = 2, max = 10) List<@NotNull @PositiveOrZero Integer> observedHeadwaySeconds,
        @NotNull Instant firstConfirmedAt,
        @NotEmpty @Size(max = 100) List<@NotBlank @Size(max = 200) String> evidenceIds) {

    public ConfirmedBunchingEvent toDomain() {
        return new ConfirmedBunchingEvent(
                bunchingEventId,
                serviceId,
                directionId,
                monitoredStopCode,
                status,
                expectedHeadwaySeconds,
                observedHeadwaySeconds,
                firstConfirmedAt,
                evidenceIds);
    }
}
