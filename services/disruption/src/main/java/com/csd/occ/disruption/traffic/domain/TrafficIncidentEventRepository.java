package com.csd.occ.disruption.traffic.domain;

import java.util.UUID;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TrafficIncidentEventRepository extends JpaRepository<TrafficIncidentEvent, UUID> {
    @Query(value = """
            select evidence::text from disruption.disruption_event
            where disruption_id = :disruptionId and kind = 'TRAFFIC_INCIDENT'
            order by triggered_at desc, id desc limit 1
            """, nativeQuery = true)
    Optional<String> findLatestEvidenceByDisruptionId(UUID disruptionId);
}
