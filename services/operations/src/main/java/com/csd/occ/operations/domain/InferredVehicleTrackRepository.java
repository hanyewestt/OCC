package com.csd.occ.operations.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InferredVehicleTrackRepository extends JpaRepository<InferredVehicleTrack, UUID> {
    @org.springframework.data.jpa.repository.Query(value = "select pg_try_advisory_xact_lock(6768005)", nativeQuery = true)
    boolean lockProjection();
    List<InferredVehicleTrack> findByStatusNot(String status);
    List<InferredVehicleTrack> findByLastSeenAtAfterOrderByServiceIdAscIdAsc(Instant cutoff);
    long deleteByLastSeenAtBefore(Instant cutoff);
}
