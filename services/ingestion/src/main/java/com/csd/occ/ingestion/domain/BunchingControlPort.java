package com.csd.occ.ingestion.domain;

import java.util.List;
import java.util.UUID;
import java.util.Optional;
import java.time.Instant;

public interface BunchingControlPort {
    default List<UUID> protectedDemoRuns() { throw new UnsupportedOperationException("Retention protection is unavailable"); }
    void selectDemo(UUID runId);
    void selectLive();
    List<DemoBunchingAlert> alerts(UUID runId);
    Optional<CurrentEvidence> currentEvidence(UUID runId, UUID episodeId);
    LiveBunchingAlertPage currentLiveAlerts(int page, int size);
    record CurrentEvidence(UUID demoRunId, UUID bunchingEventId, String serviceId, String stopCode, int directionId,
                           String state, UUID rawObservationId, Instant fetchedAt, Instant validUntil, boolean fresh,
                           List<UUID> affectedObservationIds) {}
}
