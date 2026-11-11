package com.csd.occ.planning.advisory.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdvisoryModelEvidenceRepository extends JpaRepository<AdvisoryModelEvidence, UUID> {
    Page<AdvisoryModelEvidence> findAllByOrderByProposalIdAsc(Pageable pageable);

    @Modifying
    @Query(value = """
            delete from planning.advisory_model_evidence evidence
             where evidence.created_at < :cutoff
               and not exists (
                   select 1 from planning.advisory_proposal proposal
                    where proposal.id = evidence.proposal_id and proposal.status = 'PENDING'
               )
               and not exists (
                   select 1 from planning.issued_instruction instruction
                    where instruction.proposal_id = evidence.proposal_id
                      and instruction.status in ('ISSUED', 'ACKNOWLEDGED')
               )
            """, nativeQuery = true)
    int deleteExpiredBefore(@Param("cutoff") java.time.Instant cutoff);
}
