package com.csd.occ.disruption.bunching.domain;

import java.util.UUID;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlertEventRepository extends JpaRepository<AlertEvent, UUID> {
    java.util.Optional<AlertEvent> findByIdAndKind(UUID id, String kind);
    long deleteByDemoRunId(UUID demoRunId);
    @Query(value = "select event.demo_run_id from disruption.disruption_event event join disruption.planning_outbox work on work.event_id = event.id where event.demo_run_id is not null union select demo_run_id from disruption.source_processing_state where demo_run_id is not null and status = 'DEFERRED' union select demo_run_id from disruption.consumer_state where mode = 'DEMO'", nativeQuery = true)
    List<UUID> findProtectedDemoRunIds();
    @org.springframework.data.jpa.repository.Modifying
    @Query("delete from AlertEvent event where event.kind = 'BUNCHING' and event.triggeredAt < :cutoff and exists (select cleared from AlertEvent cleared where cleared.kind = 'BUNCHING' and cleared.alertId = event.alertId and cleared.action = 'CLEARED' and cleared.triggeredAt < :cutoff) and not exists (select state from AlertProcessingState state where state.activeAlertId = event.alertId) and not exists (select row from DiagnosisOutbox row where row.eventId = event.id)")
    int deleteResolvedBefore(@Param("cutoff") java.time.Instant cutoff);
    List<AlertEvent> findTop20ByOrderByTriggeredAtDesc();
    List<AlertEvent> findByDemoRunIdOrderByTriggeredAtDesc(UUID demoRunId);
    List<AlertEvent> findByDemoRunIdAndClassificationOrderByTriggeredAtDesc(UUID demoRunId, String classification);
    List<AlertEvent> findByDemoRunIdAndClassificationAndKindOrderByTriggeredAtDesc(
            UUID demoRunId, String classification, String kind);
    @Query(value = """
            select event from AlertProcessingState state join AlertEvent event on event.alertId = state.activeAlertId
            where state.activeAlertId is not null
              and event.action = 'OPENED'
              and state.demoRunId is null
            order by event.triggeredAt desc
            """, countQuery = """
            select count(event) from AlertProcessingState state join AlertEvent event on event.alertId = state.activeAlertId
            where state.activeAlertId is not null
              and event.action = 'OPENED'
              and state.demoRunId is null
            """)
    Page<AlertEvent> findCurrentLiveOpenAlerts(Pageable pageable);
    @Query(value = "select pg_notify('bunching_alert', :payload)", nativeQuery = true)
    Object notifyListeners(@Param("payload") String payload);
}
