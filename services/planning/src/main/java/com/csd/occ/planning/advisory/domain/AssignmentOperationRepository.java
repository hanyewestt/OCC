package com.csd.occ.planning.advisory.domain;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AssignmentOperationRepository extends JpaRepository<AssignmentOperation, UUID> {
    Optional<AssignmentOperation> findByActorSubjectAndIdempotencyKey(String actorSubject, String idempotencyKey);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select operation from AssignmentOperation operation where operation.id=:id")
    Optional<AssignmentOperation> findForUpdate(@Param("id") UUID id);
    @Query(value = "select * from planning.bus_assignment_operation where status=:status order by last_dispatch_at nulls first, created_at, id", nativeQuery = true)
    @Transactional(readOnly = true)
    List<AssignmentOperation> findByStatusOrderByCreatedAtAscIdAsc(@Param("status") String status, Pageable page);
    @Query(value = "select * from planning.bus_assignment_operation where demo_run_id=:runId and status=:status order by created_at, id", nativeQuery = true)
    @Transactional(readOnly = true)
    List<AssignmentOperation> findByRunAndStatus(@Param("runId") UUID runId, @Param("status") String status, Pageable page);
    @Modifying
    @Transactional
    @Query("update AssignmentOperation operation set operation.lastDispatchAt=:now where operation.id=:id and operation.status='PENDING'")
    int markDispatchAttempt(@Param("id") UUID id, @Param("now") java.time.Instant now);
    @Modifying
    @Query(value = """
            INSERT INTO planning.bus_assignment_operation
                (id, actor_subject, idempotency_key, demo_run_id, bus_id, captain_user_id, expected_version,
                 status, result, created_at, updated_at)
            VALUES (:id, :actor, :key, :runId, :busId, :captain, :version, 'PENDING', '{}'::jsonb, :now, :now)
            ON CONFLICT (actor_subject, idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int insertPendingIfAbsent(@Param("id") UUID id, @Param("actor") String actor, @Param("key") String key,
            @Param("runId") UUID runId, @Param("busId") String busId, @Param("captain") UUID captain,
            @Param("version") long version, @Param("now") java.time.Instant now);
}
