package com.csd.occ.ingestion.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import com.csd.occ.ingestion.domain.PollRun;
import java.util.UUID;
import java.util.Optional;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PollRunRepository extends JpaRepository<PollRun, UUID> {
    void deleteByDemoRunId(UUID demoRunId);
    Optional<PollRun> findByIdempotencyKey(String idempotencyKey);
    Page<PollRun> findByClassification(String classification, Pageable pageable);
    Page<PollRun> findByClassificationAndStartedAtGreaterThanEqual(String classification, Instant cutoff, Pageable pageable);

    @Query("""
            select run from PollRun run
             where run.completedAt is not null
               and (run.source = 'DEMO' or run.source like 'TRAFFIC_%')
             order by run.completedAt asc, run.id asc
            """)
    List<PollRun> findCompletedTrafficPolls(Pageable pageable);

    @Query("""
            select run from PollRun run
             where run.completedAt is not null
               and (run.source = 'DEMO' or run.source like 'TRAFFIC_%')
               and (run.completedAt > :afterCompletedAt
                    or (run.completedAt = :afterCompletedAt and run.id > :afterPollRunId))
             order by run.completedAt asc, run.id asc
            """)
    List<PollRun> findCompletedTrafficPollsAfter(@org.springframework.data.repository.query.Param("afterCompletedAt") Instant afterCompletedAt,
            @org.springframework.data.repository.query.Param("afterPollRunId") UUID afterPollRunId, Pageable pageable);

    @Modifying
    @Query(value = """
            delete from ingestion.poll_run run
            where run.completed_at < :cutoff
              and not exists (select 1 from ingestion.raw_observation raw where raw.poll_run_id = run.id)
            """, nativeQuery = true)
    int deleteCompletedWithoutRawBefore(Instant cutoff);
}
