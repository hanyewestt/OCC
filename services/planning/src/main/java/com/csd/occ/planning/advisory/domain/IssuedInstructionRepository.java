package com.csd.occ.planning.advisory.domain;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface IssuedInstructionRepository extends JpaRepository<IssuedInstruction, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select i from IssuedInstruction i where i.id=:id")
    Optional<IssuedInstruction> findForUpdate(UUID id);
    List<IssuedInstruction> findByDemoRunIdAndBusIdAndStatusInOrderByIssuedAtAsc(UUID runId, String busId, List<String> statuses);
    Page<IssuedInstruction> findByDemoRunIdOrderByIssuedAtDesc(UUID runId, Pageable page);
    @org.springframework.data.jpa.repository.Modifying
    @Query("update IssuedInstruction i set i.status='CANCELLED',i.inactiveReason=:reason,i.version=i.version+1 where i.disruptionId=:id and i.status in ('ISSUED','ACKNOWLEDGED')")
    int cancelByDisruption(UUID id, String reason);
    @org.springframework.data.jpa.repository.Modifying
    @Query("update IssuedInstruction i set i.status='EXPIRED',i.inactiveReason='VALIDITY_EXPIRED',i.version=i.version+1 where i.status in ('ISSUED','ACKNOWLEDGED') and ((i.acknowledgedAt is null and i.acknowledgementDeadline<=:now) or i.applicableUntil<=:now)")
    int expireDue(java.time.Instant now);
    @org.springframework.data.jpa.repository.Modifying
    @Query("delete from IssuedInstruction i where i.status in ('EXPIRED','CANCELLED') and i.issuedAt<:cutoff and (i.acknowledgedAt is null or i.acknowledgedAt<:cutoff)")
    int deleteInactiveBefore(java.time.Instant cutoff);
}
