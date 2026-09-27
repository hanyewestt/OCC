package com.csd.occ.ingestion.domain;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface TrafficIncidentObservationRepository extends JpaRepository<TrafficIncidentObservation, UUID> {
    List<TrafficIncidentObservation> findByDemoRunIdOrderByFetchedAtDesc(UUID demoRunId);
}
