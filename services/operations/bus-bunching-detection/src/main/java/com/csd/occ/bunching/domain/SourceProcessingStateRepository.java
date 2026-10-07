package com.csd.occ.bunching.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SourceProcessingStateRepository extends JpaRepository<SourceProcessingState, UUID> {
    @Modifying
    @Query(value = "insert into bunching.source_processing_state (raw_observation_id, status, processed_at, demo_run_id, evaluated_at, threshold_seconds, bunched_group_count) values (:id, 'PROCESSED', :evaluatedAt, :demoRunId, :evaluatedAt, :threshold, :count) on conflict (raw_observation_id) do nothing", nativeQuery = true)
    int insertProcessedIfAbsent(@Param("id") UUID id, @Param("demoRunId") UUID demoRunId,
                                @Param("evaluatedAt") Instant evaluatedAt, @Param("threshold") BigDecimal threshold,
                                @Param("count") int count);
    List<SourceProcessingState> findByDemoRunIdAndStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            UUID demoRunId, String status, Instant now, Pageable pageable);
    List<SourceProcessingState> findByDemoRunIdIsNullAndStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            String status, Instant now, Pageable pageable);
    Optional<SourceProcessingState> findByRawObservationId(UUID id);
    @Modifying @Query("delete from SourceProcessingState state where state.status in ('PROCESSED', 'REJECTED') and coalesce(state.processedAt, state.rejectedAt) < :cutoff")
    int deleteCompletedBefore(@Param("cutoff") Instant cutoff);
}
