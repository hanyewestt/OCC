package com.csd.occ.ingestion.domain;
import java.util.UUID;
import java.time.Instant;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.Optional;
public interface ApplicationAuditEventRepository extends JpaRepository<ApplicationAuditEvent,UUID> {
    boolean existsBySourceKey(String sourceKey);

    @Query(value = """
            select event from ApplicationAuditEvent event
             where (:category is null or lower(event.category) like :category escape '!')
               and (:eventType is null or (lower(event.eventType) like :eventType escape '!' or lower(event.category) like :eventType escape '!'))
               and (:outcome is null or lower(event.outcome) like :outcome escape '!')
               and (:actorSubject is null or lower(event.actorSubject) like :actorSubject escape '!')
               and (:runId is null or event.runId = :runId)
               and (:entityId is null or lower(event.entityId) like :entityId escape '!')
               and event.occurredAt >= :from and event.occurredAt <= :to
             order by event.occurredAt desc, event.id desc
            """, countQuery = """
            select count(event) from ApplicationAuditEvent event
             where (:category is null or lower(event.category) like :category escape '!')
               and (:eventType is null or (lower(event.eventType) like :eventType escape '!' or lower(event.category) like :eventType escape '!'))
               and (:outcome is null or lower(event.outcome) like :outcome escape '!')
               and (:actorSubject is null or lower(event.actorSubject) like :actorSubject escape '!')
               and (:runId is null or event.runId = :runId)
               and (:entityId is null or lower(event.entityId) like :entityId escape '!')
               and event.occurredAt >= :from and event.occurredAt <= :to
            """)
    Page<ApplicationAuditEvent> search(@Param("category") String category,
            @Param("eventType") String eventType, @Param("outcome") String outcome,
            @Param("actorSubject") String actorSubject, @Param("runId") UUID runId,
            @Param("entityId") String entityId, @Param("from") Instant from, @Param("to") Instant to,
            Pageable pageable);

    Optional<ApplicationAuditEvent> findByIdAndOccurredAtGreaterThanEqual(UUID id, Instant cutoff);

    @Modifying
    @Query("delete from ApplicationAuditEvent event where event.occurredAt < :cutoff")
    int deleteBefore(@Param("cutoff") Instant cutoff);
}
