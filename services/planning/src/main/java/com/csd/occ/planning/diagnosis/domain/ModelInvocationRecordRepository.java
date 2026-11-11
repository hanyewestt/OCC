package com.csd.occ.planning.diagnosis.domain;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ModelInvocationRecordRepository extends JpaRepository<ModelInvocationRecord, UUID> {
    Page<ModelInvocationRecord> findAllByOrderByCompletedAtDesc(Pageable pageable);
    Page<ModelInvocationRecord> findAllByOrderByIdAsc(Pageable pageable);

    @Modifying
    @Query(value = """
            delete from planning.model_invocation_record invocation
             where invocation.completed_at < :cutoff
               and not exists (
                   select 1 from planning.planning_request request
                    where request.id = invocation.request_id
                      and request.status in ('PENDING', 'PROCESSING', 'FAILED_RETRYABLE')
               )
               and not exists (
                   select 1 from planning.diagnosis_result diagnosis
                   join planning.recommendation_job job on job.diagnosis_id = diagnosis.id
                    where diagnosis.request_id = invocation.request_id
                      and job.status in ('PENDING', 'PROCESSING')
               )
               and not exists (
                   select 1 from planning.recommendation recommendation
                    where recommendation.recommendation_id = invocation.recommendation_id
                      and not exists (
                          select 1 from planning.recommendation_decision decision
                           where decision.recommendation_id = recommendation.recommendation_id
                      )
               )
            """, nativeQuery = true)
    int deleteExpiredBefore(@Param("cutoff") java.time.Instant cutoff);
}
