package com.csd.occ.bunching.domain;

import java.util.UUID;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlertEventRepository extends JpaRepository<AlertEvent, UUID> {
    @org.springframework.data.jpa.repository.Modifying
    @Query("delete from AlertEvent event where event.triggeredAt < :cutoff and event.action = 'CLEARED' and not exists (select state from AlertProcessingState state where state.activeAlertId = event.alertId) and not exists (select row from DiagnosisOutbox row where row.eventId = event.id)")
    int deleteResolvedBefore(@Param("cutoff") java.time.Instant cutoff);
    List<AlertEvent> findTop20ByOrderByTriggeredAtDesc();
    List<AlertEvent> findByDemoRunIdOrderByTriggeredAtDesc(UUID demoRunId);
    List<AlertEvent> findByDemoRunIdAndClassificationOrderByTriggeredAtDesc(UUID demoRunId, String classification);
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
