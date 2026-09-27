package com.csd.occ.bunching.domain;

import java.util.UUID;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlertEventRepository extends JpaRepository<AlertEvent, UUID> {
    List<AlertEvent> findTop20ByOrderByTriggeredAtDesc();
    List<AlertEvent> findByDemoRunIdOrderByTriggeredAtDesc(UUID demoRunId);
    List<AlertEvent> findByDemoRunIdAndClassificationOrderByTriggeredAtDesc(UUID demoRunId, String classification);
    @Query(value = """
            select event from AlertState state join AlertEvent event on event.alertId = state.activeAlertId
            where state.activeAlertId is not null
              and event.action = 'OPENED'
              and event.classification = 'OBSERVATION_ONLY'
            order by event.triggeredAt desc
            """, countQuery = """
            select count(event) from AlertState state join AlertEvent event on event.alertId = state.activeAlertId
            where state.activeAlertId is not null
              and event.action = 'OPENED'
              and event.classification = 'OBSERVATION_ONLY'
            """)
    Page<AlertEvent> findCurrentLiveOpenAlerts(Pageable pageable);
    @Query(value = "select pg_notify('bunching_alert', :payload)", nativeQuery = true)
    Object notifyListeners(@Param("payload") String payload);
}
