package com.csd.occ.planning.diagnosis.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record DiagnosisEvidenceDetail(String evidenceId, String incidentType, String message,
        Instant reportTime, Instant fetchedAt, Instant firstConfirmedAt, Long secondsBeforeFirstConfirmed,
        Long distanceMetersFromSuppliedBusPosition, String busPositionEvidenceId, String monitoredStopCode,
        BigDecimal latitude, BigDecimal longitude) {

    public DiagnosisEvidenceDetail(String evidenceId, String incidentType, String message,
            Instant reportTime, Instant fetchedAt, Instant firstConfirmedAt, Long secondsBeforeFirstConfirmed,
            Long distanceMetersFromSuppliedBusPosition, String busPositionEvidenceId, String monitoredStopCode) {
        this(evidenceId, incidentType, message, reportTime, fetchedAt, firstConfirmedAt,
                secondsBeforeFirstConfirmed, distanceMetersFromSuppliedBusPosition, busPositionEvidenceId,
                monitoredStopCode, null, null);
    }
}
