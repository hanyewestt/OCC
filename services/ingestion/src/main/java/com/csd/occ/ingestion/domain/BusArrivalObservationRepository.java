package com.csd.occ.ingestion.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;

public interface BusArrivalObservationRepository extends JpaRepository<BusArrivalObservation, UUID> {
    long deleteByDemoRunId(UUID demoRunId);
    List<BusArrivalObservation> findByPollRunId(UUID pollRunId);
    List<BusArrivalObservation> findByRawObservationIdIn(List<UUID> rawObservationIds);
    Page<BusArrivalObservation> findByStopCodeAndServiceIdAndFetchedAtBetweenOrderByFetchedAtDesc(
            String stopCode, String serviceId, Instant from, Instant to, Pageable pageable);

    @Query("""
            select new com.csd.occ.ingestion.domain.DemoSimulationArrivalDetails(
                assignment.vehicleRegistrationNumber, assignment.operator,
                assignment.vehicleType, assignment.captainCode, arrival.estimatedArrivalAt)
            from BusArrivalObservation arrival, DemoVehicleAssignment assignment
            where arrival.id = :observationId
              and arrival.classification = 'SIMULATION'
              and arrival.demoRunId = :demoRunId
              and arrival.tripId is not null
              and assignment.id.demoRunId = arrival.demoRunId
              and assignment.id.tripId = arrival.tripId
            """)
    Optional<DemoSimulationArrivalDetails> findSimulationArrivalDetails(@Param("demoRunId") UUID demoRunId,
            @Param("observationId") UUID observationId);

    @Query(value = """
            select id as id, stop_code as stopCode, service_id as serviceId, operator as operator,
                   direction_id as directionId, visit_order as visitOrder, visit_number as visitNumber,
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

    @Query(value = """
            select observation.* from ingestion.bus_arrival_observation observation
            where observation.classification = 'OBSERVATION_ONLY'
              and (:stopCode is null or observation.stop_code = :stopCode)
              and (:serviceId is null or observation.service_id = :serviceId)
              and (cast(:from as timestamptz) is null or observation.fetched_at >= cast(:from as timestamptz))
              and (cast(:to as timestamptz) is null or observation.fetched_at <= cast(:to as timestamptz))
            order by observation.fetched_at desc, observation.service_id, observation.visit_order
            """, countQuery = """
            select count(*) from ingestion.bus_arrival_observation observation
            where observation.classification = 'OBSERVATION_ONLY'
              and (:stopCode is null or observation.stop_code = :stopCode)
              and (:serviceId is null or observation.service_id = :serviceId)
              and (cast(:from as timestamptz) is null or observation.fetched_at >= cast(:from as timestamptz))
              and (cast(:to as timestamptz) is null or observation.fetched_at <= cast(:to as timestamptz))
            """, nativeQuery = true)
    Page<BusArrivalObservation> findHistory(@Param("stopCode") String stopCode,
            @Param("serviceId") String serviceId, @Param("from") Instant from,
            @Param("to") Instant to, Pageable pageable);

    @Modifying
    @Query("delete from BusArrivalObservation observation where observation.classification = 'OBSERVATION_ONLY' and observation.fetchedAt < :cutoff")
    int deleteExpiredLiveObservations(@Param("cutoff") Instant cutoff);
}
