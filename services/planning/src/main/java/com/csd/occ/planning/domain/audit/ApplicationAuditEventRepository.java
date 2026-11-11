package com.csd.occ.planning.domain.audit;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Modifying;

public interface ApplicationAuditEventRepository extends JpaRepository<ApplicationAuditEvent, UUID> {
    boolean existsBySourceKey(String sourceKey);

    @Query("""
            select event from ApplicationAuditEvent event
             where event.occurredAt >= :from and event.occurredAt <= :to
               and (:category is null or event.category = :category)
               and (:eventType is null or event.eventType = :eventType)
               and (:outcome is null or event.outcome = :outcome)
               and (:actorSubject is null or event.actorSubject = :actorSubject)
               and (:runId is null or event.runId = :runId)
               and (:entityId is null or event.entityId = :entityId)
            """)
    Page<ApplicationAuditEvent> findForAdmin(@Param("from") Instant from, @Param("to") Instant to,
            @Param("category") String category, @Param("eventType") String eventType,
            @Param("outcome") String outcome, @Param("actorSubject") String actorSubject,
            @Param("runId") UUID runId, @Param("entityId") String entityId, Pageable pageable);

    @Modifying
    @Query("delete from ApplicationAuditEvent event where event.occurredAt < :cutoff")
    int deleteBefore(@Param("cutoff") Instant cutoff);
}
