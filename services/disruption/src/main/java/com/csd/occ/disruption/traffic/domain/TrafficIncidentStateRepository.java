package com.csd.occ.disruption.traffic.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrafficIncidentStateRepository extends JpaRepository<TrafficIncidentState, UUID> {
    Optional<TrafficIncidentState> findByDisruptionId(UUID disruptionId);
    Page<TrafficIncidentState> findByStatusOrderByUpdatedAtDesc(String status, Pageable pageable);
    Optional<TrafficIncidentState> findByClassificationAndDemoRunIdAndSourceAndSourceIncidentKey(
            String classification, UUID demoRunId, String source, String sourceIncidentKey);
    Optional<TrafficIncidentState> findByClassificationAndDemoRunIdIsNullAndSourceAndSourceIncidentKey(
            String classification, String source, String sourceIncidentKey);
    List<TrafficIncidentState> findByClassificationAndDemoRunIdAndSourceAndStatus(
            String classification, UUID demoRunId, String source, String status);
    List<TrafficIncidentState> findByClassificationAndDemoRunIdIsNullAndSourceAndStatus(
            String classification, String source, String status);
}
