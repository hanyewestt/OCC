package com.csd.occ.bunching.domain;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface DemoAlertStateRepository extends JpaRepository<DemoAlertState, DemoAlertStateId> {
    @Modifying @Query(value = "insert into bunching.demo_alert_state (demo_run_id,service_id,stop_code,direction_id,last_fetched_at) values (:run,:service,:stop,:direction,:fetched) on conflict do nothing", nativeQuery = true)
    int insertIfAbsent(@Param("run") java.util.UUID run, @Param("service") String service, @Param("stop") String stop, @Param("direction") int direction, @Param("fetched") Instant fetched);
    @Lock(LockModeType.PESSIMISTIC_WRITE) Optional<DemoAlertState> findWithLockById(DemoAlertStateId id);
}
