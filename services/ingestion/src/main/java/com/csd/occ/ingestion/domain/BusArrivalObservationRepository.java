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
    Optional<BusArrivalObservation> findFirstByDemoRunIdAndTripIdAndClassificationOrderByFetchedAtDescIdDesc(
            UUID demoRunId, String tripId, String classification);
    long deleteByDemoRunId(UUID demoRunId);
    List<BusArrivalObservation> findByPollRunId(UUID pollRunId);
    List<BusArrivalObservation> findByRawObservationIdIn(List<UUID> rawObservationIds);
    Page<BusArrivalObservation> findByStopCodeAndServiceIdAndFetchedAtBetweenOrderByFetchedAtDesc(
            String stopCode, String serviceId, Instant from, Instant to, Pageable pageable);

    @Query("""
            select new com.csd.occ.ingestion.domain.DemoSimulationArrivalDetails(
                assignment.vehicleRegistrationNumber, assignment.operator,
                assignment.vehicleType, assignment.captainCode, arrival.estimatedArrivalAt,
                coalesce(assignment.busId, assignment.id.tripId))
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

    /**
     * Latest live prediction set per polled stop plus the latest replay step of the active demo run.
     * Rows without a direction or arrival estimate are insufficient evidence and are not paced.
     */
    @Query(value = """
            select live.classification as classification, live.stop_code as stopCode,
                   live.service_id as serviceId, live.direction_id as directionId,
                   live.estimated_arrival_at as estimatedArrivalAt, live.fetched_at as fetchedAt,
                   cast(null as varchar) as vehicleReference
            from ingestion.current_bus_arrival live
            where live.direction_id is not null and live.estimated_arrival_at is not null
            union all
            select simulation.classification, simulation.stop_code, simulation.service_id, simulation.direction_id,
                   simulation.estimated_arrival_at, simulation.fetched_at, assignment.vehicle_registration_number
            from ingestion.bus_arrival_observation simulation
            join ingestion.demo_vehicle_assignment assignment
              on assignment.demo_run_id = simulation.demo_run_id and assignment.trip_id = simulation.trip_id
            where simulation.classification = 'SIMULATION'
              and simulation.direction_id is not null and simulation.estimated_arrival_at is not null
              and simulation.poll_run_id = (
                  select poll.id from ingestion.poll_run poll
                  join ingestion.demo_run run on run.id = poll.demo_run_id
                  where run.status in ('PREPARING', 'RUNNING', 'REPLAY_COMPLETE') and poll.outcome = 'SUCCESS'
                    and poll.source = 'DEMO'
                  order by poll.started_at desc
                  limit 1)
            """, nativeQuery = true)
    List<PacingArrivalView> findPacingArrivals();

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
