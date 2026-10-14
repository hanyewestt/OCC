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
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "insert into bunching.source_processing_state (raw_observation_id, status, processed_at, demo_run_id, evaluated_at, threshold_seconds, bunched_group_count) values (:id, 'PROCESSED', :evaluatedAt, :demoRunId, :evaluatedAt, :threshold, :count) on conflict (raw_observation_id) do update set status = 'PROCESSED', processed_at = excluded.processed_at, evaluated_at = excluded.evaluated_at, threshold_seconds = excluded.threshold_seconds, bunched_group_count = excluded.bunched_group_count, next_attempt_at = null, reason = null, version = source_processing_state.version + 1 where source_processing_state.status = 'DEFERRED' and source_processing_state.demo_run_id is not distinct from excluded.demo_run_id", nativeQuery = true)
    int insertProcessedIfAbsent(@Param("id") UUID id, @Param("demoRunId") UUID demoRunId,
                                @Param("evaluatedAt") Instant evaluatedAt, @Param("threshold") BigDecimal threshold,
                                @Param("count") int count);
    List<SourceProcessingState> findByDemoRunIdAndStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            UUID demoRunId, String status, Instant now, Pageable pageable);
    List<SourceProcessingState> findByDemoRunIdIsNullAndStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            String status, Instant now, Pageable pageable);
    Optional<SourceProcessingState> findByRawObservationId(UUID id);
    @Query("select state from SourceProcessingState state where state.status in ('PROCESSED', 'REJECTED') and coalesce(state.processedAt, state.rejectedAt) < :cutoff and state.sourceSequence is not null and not exists (select event from AlertEvent event where event.rawObservationId = state.rawObservationId)")
    List<SourceProcessingState> findRetentionCandidates(@Param("cutoff") Instant cutoff);
}
