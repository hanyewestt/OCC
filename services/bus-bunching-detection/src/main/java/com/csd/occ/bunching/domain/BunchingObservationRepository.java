package com.csd.occ.bunching.domain;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** No update/delete methods: evidence is immutable after the first successful commit. */
public interface BunchingObservationRepository extends Repository<BunchingObservation, UUID> {
    Optional<BunchingObservation> findByRawObservationId(UUID rawObservationId);

    // PostgreSQL's unique constraint serializes concurrent retries of the same source ID.
    @Modifying
    @Query(value = """
            INSERT INTO bunching.bunching_observation
                (raw_observation_id, poll_run_id, reference_version_id, fetched_at, evaluated_at,
                 threshold_seconds, stale_after_seconds, rule_version, classification,
                 bunched_group_count, evidence)
            VALUES (:rawId, :pollId, CAST(NULLIF(:referenceId, '') AS uuid), :fetchedAt, :evaluatedAt,
                    :threshold, :staleAfter, :ruleVersion, 'OBSERVATION_ONLY', :bunchedCount,
                    CAST(:evidence AS jsonb))
            ON CONFLICT (raw_observation_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("rawId") UUID rawId,
                       @Param("pollId") UUID pollId,
                       @Param("referenceId") String referenceId,
                       @Param("fetchedAt") Instant fetchedAt,
                       @Param("evaluatedAt") Instant evaluatedAt,
                       @Param("threshold") BigDecimal threshold,
                       @Param("staleAfter") BigDecimal staleAfter,
                       @Param("ruleVersion") String ruleVersion,
                       @Param("bunchedCount") int bunchedCount,
                       @Param("evidence") String evidence);
}
