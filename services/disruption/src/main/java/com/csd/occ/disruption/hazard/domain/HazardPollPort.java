package com.csd.occ.disruption.hazard.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface HazardPollPort {
    Page page(String kind, Instant afterAt, UUID afterId);
    record Page(List<Poll> polls, boolean hasMore) {}
    record Poll(UUID pollRunId, String source, String scope, String outcome, boolean complete,
                String classification, UUID demoRunId, Instant completedAt, List<Observation> observations) {}
    record Observation(UUID id, UUID rawObservationId, Fact facts, Instant fetchedAt) {}
    record Fact(String kind, String sourceEventKey, String sourceAction, Instant reportedAt,
                Instant effectiveFrom, Instant effectiveUntil, String title, String description,
                String severity, String instruction, List<String> references, String line, String direction,
                String serviceStatus, String department, List<Location> locations) {}
    record Location(String type, String sourceReference, String description, BigDecimal latitude,
                    BigDecimal longitude, BigDecimal radiusKm, String geometryRole, String provenance) {}
}
