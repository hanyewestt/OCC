package com.csd.occ.planning.advisory.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssignmentBusGuardRepository extends JpaRepository<AssignmentBusGuard, AssignmentBusGuardId> {
    @Modifying
    @Query(value = "INSERT INTO planning.bus_assignment_guard (demo_run_id,bus_id) VALUES (:runId,:busId) ON CONFLICT DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("runId") UUID runId, @Param("busId") String busId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select guard from AssignmentBusGuard guard where guard.id.demoRunId=:runId and guard.id.busId=:busId")
    Optional<AssignmentBusGuard> findForUpdate(@Param("runId") UUID runId, @Param("busId") String busId);
}
