package com.csd.occ.planning.diagnosis.api.dto;

import com.csd.occ.planning.diagnosis.domain.TrafficIncidentEventFacts;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Immutable traffic incident lifecycle facts; Planning records these with an explicit no-action policy in phase 1.")
public record TrafficIncidentEventFactsRequest(
        @NotNull UUID eventId,
        @NotNull UUID disruptionId,
        @NotBlank @Size(max = 20) String action,
        @NotBlank @Size(max = 30) String classification,
        UUID demoRunId,
        @NotNull UUID sourceObservationId,
        Long sourceRevision,
        @NotNull UUID pollRunId,
        @NotBlank @Size(max = 40) String source,
        @NotBlank @Size(min = 64, max = 64) String sourceIncidentKey,
        @NotBlank @Size(max = 60) String incidentType,
        @NotBlank @Size(max = 500) String message,
        @NotNull BigDecimal latitude,
        @NotNull BigDecimal longitude,
        Instant observedAt,
        @NotNull Instant fetchedAt,
        @NotNull Instant eventAt) {
    public TrafficIncidentEventFactsRequest(UUID eventId, UUID disruptionId, String action, String classification,
            UUID demoRunId, UUID sourceObservationId, UUID pollRunId, String source, String sourceIncidentKey,
            String incidentType, String message, BigDecimal latitude, BigDecimal longitude, Instant observedAt,
            Instant fetchedAt, Instant eventAt) {
        this(eventId, disruptionId, action, classification, demoRunId, sourceObservationId, null, pollRunId, source,
                sourceIncidentKey, incidentType, message, latitude, longitude, observedAt, fetchedAt, eventAt);
    }

    public TrafficIncidentEventFacts toDomain() {
        return new TrafficIncidentEventFacts(eventId, disruptionId, action, classification, demoRunId,
                sourceObservationId, sourceRevision, pollRunId, source, sourceIncidentKey, incidentType, message,
                latitude, longitude, observedAt, fetchedAt, eventAt);
    }
}
