package com.csd.occ.ingestion.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface DemoAssignmentReceiptRepository extends JpaRepository<DemoAssignmentReceipt, UUID> {
    @Modifying
    @Query(value = """
            insert into ingestion.demo_assignment_receipt
                (operation_id, demo_run_id, bus_id, captain_user_id, expected_version, actor_subject,
                 outcome, http_status, before_version, after_version, created_at)
            values (:operationId, :runId, :busId, :captainUserId, :expectedVersion, :actorSubject,
                    'PENDING', 202, :expectedVersion, :expectedVersion, :createdAt)
            on conflict (operation_id) do nothing
            """, nativeQuery = true)
    int claim(@Param("operationId") UUID operationId, @Param("runId") UUID runId,
            @Param("busId") String busId, @Param("captainUserId") UUID captainUserId,
            @Param("expectedVersion") long expectedVersion, @Param("actorSubject") String actorSubject,
            @Param("createdAt") Instant createdAt);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select receipt from DemoAssignmentReceipt receipt where receipt.operationId = :operationId")
    Optional<DemoAssignmentReceipt> findLocked(@Param("operationId") UUID operationId);
}
