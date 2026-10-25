package com.csd.occ.planning.advisory.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface AdvisoryProposalRepository extends JpaRepository<AdvisoryProposal, UUID> {
    Page<AdvisoryProposal> findByDemoRunIdOrderByCreatedAtAsc(UUID runId, Pageable page);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select p from AdvisoryProposal p where p.id=:id")
    Optional<AdvisoryProposal> findForUpdate(UUID id);
    @org.springframework.data.jpa.repository.Modifying
    @Query("update AdvisoryProposal p set p.status='EXPIRED',p.version=p.version+1 where p.disruptionId=:disruptionId and p.demoRunId=:runId and p.busId=:busId and p.status='PENDING' and p.id<>:keepId")
    int expireSuperseded(UUID disruptionId, UUID runId, String busId, UUID keepId);
    @org.springframework.data.jpa.repository.Modifying
    @Query("delete from AdvisoryProposal p where p.createdAt<:cutoff and not exists (select i from IssuedInstruction i where i.proposalId=p.id)")
    int deleteUnreferencedBefore(java.time.Instant cutoff);
}
