package com.csd.occ.planning.advisory.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface AdvisoryProposalRepository extends JpaRepository<AdvisoryProposal, UUID> {
    String PENDING_GROUPS = """
        from planning.advisory_proposal p where p.demo_run_id=:runId and p.status='PENDING'
        group by p.disruption_id,p.source_revision_key,p.bus->>'serviceId',p.bus->>'directionId',
                 p.candidate_type,p.candidate->'parameters'
        """;
    @Query(value = "select (array_agg(p.id order by p.created_at,p.id))[1] " + PENDING_GROUPS
            + " order by min(p.created_at),min(p.id::text)",
            countQuery = "select count(*) from (select 1 " + PENDING_GROUPS + ") grouped", nativeQuery = true)
    Page<UUID> findPendingGroupIds(UUID runId, Pageable page);

    @Query(value = """
        select p.* from planning.advisory_proposal p where p.status='PENDING' and exists (
            select 1 from planning.advisory_proposal a where a.id in (:anchorIds)
              and p.demo_run_id=a.demo_run_id and p.disruption_id=a.disruption_id
              and p.source_revision_key=a.source_revision_key and p.candidate_type=a.candidate_type
              and p.bus->>'serviceId'=a.bus->>'serviceId' and p.bus->>'directionId'=a.bus->>'directionId'
              and p.candidate->'parameters'=a.candidate->'parameters')
        order by p.created_at,p.id
        """, nativeQuery = true)
    List<AdvisoryProposal> findPendingGroupMembers(List<UUID> anchorIds);

    Page<AdvisoryProposal> findByDemoRunIdOrderByCreatedAtAsc(UUID runId, Pageable page);
    Page<AdvisoryProposal> findByDemoRunIdAndStatusOrderByCreatedAtAsc(UUID runId, String status, Pageable page);
    boolean existsByDisruptionIdAndDemoRunIdAndBusIdAndSourceRevisionKeyAndCandidateType(
            UUID disruptionId, UUID demoRunId, String busId, String sourceRevisionKey, String candidateType);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select p from AdvisoryProposal p where p.id=:id")
    Optional<AdvisoryProposal> findForUpdate(UUID id);
    @org.springframework.data.jpa.repository.Modifying
    @Query("update AdvisoryProposal p set p.status='EXPIRED',p.version=p.version+1 where p.disruptionId=:disruptionId and p.demoRunId=:runId and p.busId=:busId and p.status='PENDING' and p.id<>:keepId")
    int expireSuperseded(UUID disruptionId, UUID runId, String busId, UUID keepId);
    @org.springframework.data.jpa.repository.Modifying
    @Query("update AdvisoryProposal p set p.status='EXPIRED',p.version=p.version+1 where p.demoRunId=:runId and p.status='PENDING'")
    int expirePendingByDemoRunId(UUID runId);
    @org.springframework.data.jpa.repository.Modifying
    @Query("delete from AdvisoryProposal p where p.createdAt<:cutoff and not exists (select i from IssuedInstruction i where i.proposalId=p.id)")
    int deleteUnreferencedBefore(java.time.Instant cutoff);
}
