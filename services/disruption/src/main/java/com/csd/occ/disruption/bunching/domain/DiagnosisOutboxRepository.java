package com.csd.occ.disruption.bunching.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DiagnosisOutboxRepository extends JpaRepository<DiagnosisOutbox, UUID> {
    @org.springframework.data.jpa.repository.Modifying
    @Query("delete from DiagnosisOutbox row where row.status in ('DELIVERED', 'FAILED_TERMINAL') and coalesce(row.deliveredAt, row.createdAt) < :cutoff")
    int deleteDeliveredOrTerminalBefore(@Param("cutoff") Instant cutoff);
    Optional<DiagnosisOutbox> findByEventId(UUID eventId);
    @Query(value = "select * from disruption.planning_outbox where "
            + "((status = 'PENDING' and next_attempt_at <= :now) or (status = 'PROCESSING' and lease_until <= :now)) "
            + "order by created_at limit :batchSize for update skip locked", nativeQuery = true)
    List<DiagnosisOutbox> findClaimable(@Param("now") Instant now, @Param("batchSize") int batchSize);
}
