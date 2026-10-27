package com.csd.occ.planning.diagnosis.api.dto;

import com.csd.occ.planning.diagnosis.domain.DiagnosisEvidenceDetail;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "Source facts associated with a diagnosis. Evidence IDs identify retained technical records.")
public record DiagnosisEvidenceDetailResponse(
        @Schema(description = "Stable source evidence reference; intended for technical investigation", maxLength = 200)
        String evidenceId,
        @Schema(maxLength = 60)
        String incidentType,
        @Schema(description = "Sanitized source description", maxLength = 500)
        String message,
        @Schema(description = "Provider-reported incident time; null means the source time was unavailable", format = "date-time", nullable = true)
        Instant reportTime,
        @Schema(description = "Fetch time is provenance only and does not establish incident chronology", format = "date-time", nullable = true)
        Instant fetchedAt,
        @Schema(description = "First confirmed irregularity time for the monitored event", format = "date-time")
        Instant firstConfirmedAt,
        @Schema(description = "Positive means the provider report was earlier; zero means the times match or the report was less than one second earlier; negative means the report was later", nullable = true)
        Long secondsBeforeFirstConfirmed,
        @Schema(description = "Approximate straight-line distance in metres from the supplied bus position; not distance from the stop", nullable = true)
        Long distanceMetersFromSuppliedBusPosition,
        @Schema(description = "Technical reference to the supplied bus-position evidence", maxLength = 200, nullable = true)
        String busPositionEvidenceId,
        @Schema(maxLength = 12)
        String monitoredStopCode) {

    public static DiagnosisEvidenceDetailResponse from(DiagnosisEvidenceDetail detail) {
        return new DiagnosisEvidenceDetailResponse(detail.evidenceId(), detail.incidentType(), detail.message(),
                detail.reportTime(), detail.fetchedAt(), detail.firstConfirmedAt(), detail.secondsBeforeFirstConfirmed(),
                detail.distanceMetersFromSuppliedBusPosition(), detail.busPositionEvidenceId(), detail.monitoredStopCode());
    }
}
