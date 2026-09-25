package com.csd.occ.bunching.domain;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface AlertStateRepository extends JpaRepository<AlertState, AlertStateId> {
    @Modifying
    @Query(value = """
            insert into bunching.alert_state (service_id, stop_code, direction_id, last_fetched_at)
            values (:serviceId, :stopCode, :directionId, :initialFetchedAt) on conflict do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("serviceId") String serviceId, @Param("stopCode") String stopCode,
                       @Param("directionId") int directionId, @Param("initialFetchedAt") Instant initialFetchedAt);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AlertState> findWithLockById(AlertStateId id);
}
