package com.csd.occ.bunching.domain;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface AlertProcessingStateRepository extends JpaRepository<AlertProcessingState, UUID> {
    @Query("select distinct state.demoRunId from AlertProcessingState state where state.demoRunId is not null and state.lastFetchedAt < :cutoff")
    java.util.List<UUID> findExpiredDemoRunIds(@Param("cutoff") Instant cutoff);
    void deleteByDemoRunId(UUID demoRunId);
    @Modifying @Query(value = "insert into bunching.alert_processing_state (id,demo_run_id,service_id,stop_code,direction_id,last_fetched_at) values (:id,:run,:service,:stop,:direction,:fetched) on conflict (demo_run_id,service_id,stop_code,direction_id) do nothing", nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("run") UUID run, @Param("service") String service, @Param("stop") String stop, @Param("direction") int direction, @Param("fetched") Instant fetched);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select state from AlertProcessingState state where ((:run is null and state.demoRunId is null) or state.demoRunId = :run) and state.serviceId = :service and state.stopCode = :stop and state.directionId = :direction")
    Optional<AlertProcessingState> findWithLock(@Param("run") UUID run, @Param("service") String service, @Param("stop") String stop, @Param("direction") int direction);
}
