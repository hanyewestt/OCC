package com.csd.occ.ingestion.domain;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface TrafficIncidentObservationRepository extends JpaRepository<TrafficIncidentObservation, UUID> {
    void deleteByDemoRunId(UUID demoRunId);
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("delete from TrafficIncidentObservation row where row.classification = 'OBSERVATION_ONLY' and row.fetchedAt < :cutoff")
    int deleteExpiredLive(@org.springframework.data.repository.query.Param("cutoff") java.time.Instant cutoff);
    List<TrafficIncidentObservation> findByDemoRunIdOrderByFetchedAtDesc(UUID demoRunId);
}
