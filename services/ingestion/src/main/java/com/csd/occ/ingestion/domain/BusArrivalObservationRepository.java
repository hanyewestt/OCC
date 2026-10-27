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
            select arrival.id as id, arrival.stop_code as stopCode, stop.description as stopName,
                   arrival.service_id as serviceId, arrival.operator as operator,
                   arrival.direction_id as directionId, arrival.visit_order as visitOrder,
                   arrival.visit_number as visitNumber, arrival.estimated_arrival_at as estimatedArrivalAt,
                   arrival.origin_code as originCode, arrival.destination_code as destinationCode,
                   arrival.monitored as monitored, arrival.latitude as latitude, arrival.longitude as longitude,
                   arrival.load_category as loadCategory, arrival.feature as feature,
                   arrival.vehicle_type as vehicleType, arrival.fetched_at as fetchedAt,
                   arrival.age_seconds as ageSeconds, arrival.reference_version_id as referenceVersionId
            from ingestion.current_bus_arrival arrival
            left join ingestion.bus_stop stop
              on stop.reference_version_id = arrival.reference_version_id
             and stop.stop_code = arrival.stop_code
            where (:stopCode is null or arrival.stop_code = :stopCode)
              and (:serviceId is null or arrival.service_id = :serviceId)
              and (:stopNamePattern is null or lower(stop.description) like lower(:stopNamePattern) escape '!')
            order by case when :sortBy = 'arrival' and arrival.estimated_arrival_at is null then 1
                          when :sortBy = 'stopName' and stop.description is null then 1 else 0 end,
                     case when :sortBy = 'arrival' and :sortDirection = 'asc'
                          then arrival.estimated_arrival_at end asc,
                     case when :sortBy = 'arrival' and :sortDirection = 'desc'
                          then arrival.estimated_arrival_at end desc,
                     case when :sortBy = 'stopName' and :sortDirection = 'asc'
                          then lower(stop.description) end asc,
                     case when :sortBy = 'stopName' and :sortDirection = 'desc'
                          then lower(stop.description) end desc,
                     arrival.service_id, arrival.visit_order nulls last, arrival.id
            """, countQuery = """
            select count(*) from ingestion.current_bus_arrival arrival
            left join ingestion.bus_stop stop
              on stop.reference_version_id = arrival.reference_version_id
             and stop.stop_code = arrival.stop_code
            where (:stopCode is null or arrival.stop_code = :stopCode)
              and (:serviceId is null or arrival.service_id = :serviceId)
              and (:stopNamePattern is null or lower(stop.description) like lower(:stopNamePattern) escape '!')
            """, nativeQuery = true)
    Page<CurrentBusArrivalView> findCurrent(@Param("stopCode") String stopCode,
            @Param("serviceId") String serviceId, @Param("stopNamePattern") String stopNamePattern,
            @Param("sortBy") String sortBy, @Param("sortDirection") String sortDirection, Pageable pageable);

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
