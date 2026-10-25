package com.csd.occ.planning.advisory.domain;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;

public interface PublicNoticeRepository extends JpaRepository<PublicNotice, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select n from PublicNotice n where n.id=:id")
    Optional<PublicNotice> findForUpdate(UUID id);
    Page<PublicNotice> findAllByOrderByCreatedAtDesc(Pageable page);
    @Query("""
        select n from PublicNotice n where n.status='PUBLISHED' and n.classification=:classification
        and n.demoRunId is not distinct from :runId and n.effectiveFrom<=:now and n.effectiveUntil>:now
        order by n.publishedAt desc,n.id
        """)
    Page<PublicNotice> findCurrent(String classification, UUID runId, Instant now, Pageable page);
    @Modifying @Query("update PublicNotice n set n.status='WITHDRAWN',n.withdrawnAt=:now,n.version=n.version+1 where n.disruptionId=:id and n.status <> 'WITHDRAWN'")
    int withdrawSource(UUID id, Instant now);
    @Modifying @Query("delete from PublicNotice n where n.effectiveUntil<:cutoff and n.createdAt<:cutoff")
    int deleteExpiredBefore(Instant cutoff);
}
