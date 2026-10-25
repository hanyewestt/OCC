package com.csd.occ.disruption.hazard.domain;

import java.time.Instant;
import java.util.UUID;

public record HazardEventSnapshot(UUID eventId, UUID disruptionId, String action, String inactiveReason,
        String classification, UUID demoRunId, UUID sourceObservationId, UUID pollRunId,
        Instant eventAt, HazardPollPort.Fact facts) {}
