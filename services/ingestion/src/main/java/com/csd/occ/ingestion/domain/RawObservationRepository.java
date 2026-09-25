package com.csd.occ.ingestion.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import com.csd.occ.ingestion.domain.RawObservation;
import java.util.UUID;
import java.time.Instant;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RawObservationRepository extends JpaRepository<RawObservation, UUID> {
    @Modifying
    long deleteByFetchedAtBefore(Instant cutoff);

    @Query("""
            select raw from RawObservation raw join PollRun run on run.id = raw.pollRunId
            where raw.feedSequence > :afterSequence and raw.source = 'DATAMALL'
              and (raw.sourceKey like 'stop:%' or raw.sourceKey like 'service:%:stop:%')
              and raw.classification = 'OBSERVATION_ONLY'
              and raw.httpStatus between 200 and 299 and run.outcome = 'SUCCESS'
            order by raw.feedSequence
            """)
    Page<RawObservation> findBunchingFeedAfter(@Param("afterSequence") long afterSequence, Pageable pageable);

    @Query("""
            select raw from RawObservation raw join PollRun run on run.id = raw.pollRunId
            where raw.id = :id and raw.source = 'DATAMALL'
              and (raw.sourceKey like 'stop:%' or raw.sourceKey like 'service:%:stop:%')
              and raw.classification = 'OBSERVATION_ONLY' and raw.httpStatus between 200 and 299
              and run.outcome = 'SUCCESS'
            """)
    java.util.Optional<RawObservation> findBunchingFeedById(@Param("id") UUID id);
}
