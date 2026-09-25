package com.csd.occ.ingestion.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;

public interface BusArrivalObservationRepository extends JpaRepository<BusArrivalObservation, UUID> {
    List<BusArrivalObservation> findByPollRunId(UUID pollRunId);
    List<BusArrivalObservation> findByRawObservationIdIn(List<UUID> rawObservationIds);
    Page<BusArrivalObservation> findByStopCodeAndServiceIdAndFetchedAtBetweenOrderByFetchedAtDesc(
            String stopCode, String serviceId, Instant from, Instant to, Pageable pageable);

    @Query(value = """
            select id as id, stop_code as stopCode, service_id as serviceId, operator as operator,
                   direction_id as directionId, visit_order as visitOrder,
                   estimated_arrival_at as estimatedArrivalAt, origin_code as originCode,
                   destination_code as destinationCode, monitored as monitored, latitude as latitude,
                   longitude as longitude, load_category as loadCategory, feature as feature,
                   vehicle_type as vehicleType, fetched_at as fetchedAt, age_seconds as ageSeconds,
                   reference_version_id as referenceVersionId
            from ingestion.current_bus_arrival
            where (:stopCode is null or stop_code = :stopCode)
              and (:serviceId is null or service_id = :serviceId)
            order by estimated_arrival_at nulls last, service_id, visit_order
            """, countQuery = """
            select count(*) from ingestion.current_bus_arrival
            where (:stopCode is null or stop_code = :stopCode)
              and (:serviceId is null or service_id = :serviceId)
            """, nativeQuery = true)
    Page<CurrentBusArrivalView> findCurrent(@Param("stopCode") String stopCode,
            @Param("serviceId") String serviceId, Pageable pageable);

    @Query("""
            select observation from BusArrivalObservation observation
            where (:stopCode is null or observation.stopCode = :stopCode)
              and (:serviceId is null or observation.serviceId = :serviceId)
              and (:from is null or observation.fetchedAt >= :from)
              and (:to is null or observation.fetchedAt <= :to)
            order by observation.fetchedAt desc, observation.serviceId, observation.visitOrder
            """)
    Page<BusArrivalObservation> findHistory(@Param("stopCode") String stopCode,
            @Param("serviceId") String serviceId, @Param("from") Instant from,
            @Param("to") Instant to, Pageable pageable);

    @Modifying
    long deleteByFetchedAtBefore(Instant cutoff);
}
