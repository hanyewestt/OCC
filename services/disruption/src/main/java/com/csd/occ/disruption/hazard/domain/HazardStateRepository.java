package com.csd.occ.disruption.hazard.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface HazardStateRepository extends JpaRepository<HazardState, UUID> {
    java.util.Optional<HazardState> findByDisruptionId(UUID disruptionId);
    @Query("""
        select h from HazardState h where h.status <> 'CLEARED' and h.kind=coalesce(:kind,h.kind)
        and (h.effectiveUntil is null or h.effectiveUntil > :now)
        and (h.facts is not null)
        and (h.classification='OBSERVATION_ONLY' or h.demoRunId=:runId)
        order by h.updatedAt desc,h.id
        """)
    Page<HazardState> findVisible(String kind, UUID runId, Instant now, Pageable page);
    @Query("""
        select h from HazardState h where h.source=:source and h.scope=:scope and h.classification=:classification
        and h.demoRunId is not distinct from :runId
        """)
    List<HazardState> findScope(String source, String scope, String classification, UUID runId);
    @Query("""
        select h from HazardState h where h.source=:source and h.scope=:scope and h.classification=:classification
        and h.demoRunId is not distinct from :runId and (h.status <> 'CLEARED' or h.updatedAt >= :cutoff)
        """)
    List<HazardState> findRetainedScope(String source, String scope, String classification, UUID runId, Instant cutoff);
    @Modifying
    @Query(value = """
        delete from disruption.hazard_state state
        where state.status='CLEARED' and state.updated_at < :cutoff
          and not exists (
            select 1 from disruption.disruption_event event
            join disruption.planning_outbox work on work.event_id=event.id
            where event.disruption_id=state.disruption_id
          )
        """, nativeQuery = true)
    int deleteClearedBefore(Instant cutoff);
    @Query(value = """
        select evidence::text from disruption.disruption_event
        where disruption_id=:disruptionId and kind in ('FLOOD','RAIL_DISRUPTION','ROADWORKS')
        order by triggered_at desc,id desc limit 1
        """, nativeQuery = true)
    java.util.Optional<String> findLatestHazardEventEvidence(UUID disruptionId);
    Page<HazardState> findAllByOrderByUpdatedAtDesc(Pageable page);
    List<HazardState> findByStatusIn(List<String> statuses);
    @Query(value = "select exists(select 1 from disruption.disruption_event where id=:eventId)", nativeQuery = true)
    boolean eventExists(UUID eventId);
    @Modifying @Query(value = """
        insert into disruption.disruption_event
        (id,disruption_id,kind,action,classification,demo_run_id,source_observation_id,poll_run_id,fetched_at,triggered_at,evidence)
        values (:id,:disruptionId,:kind,:action,:classification,:runId,:observationId,:pollId,:fetchedAt,:at,cast(:payload as jsonb))
        on conflict (id) do nothing
        """, nativeQuery = true)
    int recordEvent(UUID id, UUID disruptionId, String kind, String action, String classification, UUID runId,
                    UUID observationId, UUID pollId, Instant fetchedAt, Instant at, String payload);
}
