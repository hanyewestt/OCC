package com.csd.occ.disruption.traffic.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface TrafficPollCheckpointRepository extends JpaRepository<TrafficPollCheckpoint, Integer> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select checkpoint from TrafficPollCheckpoint checkpoint where checkpoint.id = 1")
    Optional<TrafficPollCheckpoint> findForUpdate();
}
