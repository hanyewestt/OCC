package com.csd.occ.planning.diagnosis.domain;

import java.time.Instant;

public record DiagnosisEvidenceDetail(String evidenceId, String incidentType, String message,
        Instant reportTime, Instant fetchedAt, Instant firstConfirmedAt, Long secondsBeforeFirstConfirmed,
        Long distanceMetersFromSuppliedBusPosition, String busPositionEvidenceId, String monitoredStopCode) { }
