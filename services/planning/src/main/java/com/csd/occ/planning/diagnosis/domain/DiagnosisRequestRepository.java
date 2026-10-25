package com.csd.occ.planning.diagnosis.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DiagnosisRequestRepository extends JpaRepository<DiagnosisRequest, UUID> {
    @Query(value = """
            select * from planning.planning_request
            where event_type = 'BUS_BUNCHING' and input_snapshot is not null
              and cast(input_snapshot as jsonb) -> 'event' ->> 'classification' = :classification
              and coalesce(cast(input_snapshot as jsonb) -> 'event' ->> 'demoRunId', '') = :runId
            order by created_at desc, id
            """, countQuery = """
            select count(*) from planning.planning_request
            where event_type = 'BUS_BUNCHING' and input_snapshot is not null
              and cast(input_snapshot as jsonb) -> 'event' ->> 'classification' = :classification
              and coalesce(cast(input_snapshot as jsonb) -> 'event' ->> 'demoRunId', '') = :runId
            """, nativeQuery = true)
    org.springframework.data.domain.Page<DiagnosisRequest> findScoped(
            @Param("classification") String classification, @Param("runId") String runId,
            org.springframework.data.domain.Pageable pageable);
    @Query(value = """
            with ranked as (
                select request.*, row_number() over (
                    partition by cast(request.input_snapshot as jsonb)->>'disruptionId'
                    order by request.created_at desc, request.id desc
                ) as request_rank
                from planning.planning_request request
                where request.event_type in ('HAZARD','TRAFFIC_INCIDENT') and request.input_snapshot is not null
                  and cast(request.input_snapshot as jsonb)->>'classification' = :classification
                  and coalesce(cast(request.input_snapshot as jsonb)->>'demoRunId', '') = :runId
            )
            select id,event_type,producer,event_id,schema_version,status,attempt_count,next_attempt_at,lease_until,
                   failure_code,policy_outcome,created_at,updated_at,version,input_snapshot
            from ranked where request_rank=1 and status <> 'COMPLETED'
            order by created_at desc,id desc
            """, countQuery = """
            with ranked as (
                select request.status, row_number() over (
                    partition by cast(request.input_snapshot as jsonb)->>'disruptionId'
                    order by request.created_at desc, request.id desc
                ) as request_rank
                from planning.planning_request request
                where request.event_type in ('HAZARD','TRAFFIC_INCIDENT') and request.input_snapshot is not null
                  and cast(request.input_snapshot as jsonb)->>'classification' = :classification
                  and coalesce(cast(request.input_snapshot as jsonb)->>'demoRunId', '') = :runId
            )
            select count(*) from ranked where request_rank=1 and status <> 'COMPLETED'
            """, nativeQuery = true)
    org.springframework.data.domain.Page<DiagnosisRequest> findOpenHazardRequests(
            @Param("classification") String classification, @Param("runId") String runId,
            org.springframework.data.domain.Pageable pageable);
    @Modifying
    @Query("delete from DiagnosisRequest request where request.status = 'FAILED_TERMINAL' and request.updatedAt < :cutoff and not exists (select attempt from ModelInvocationRecord attempt where attempt.requestId = request.id) and not exists (select result from RootCauseDiagnosisEntity result where result.requestId = request.id)")
    int deleteUnreferencedTerminalBefore(@Param("cutoff") Instant cutoff);
    Optional<DiagnosisRequest> findByEventTypeAndProducerAndEventIdAndSchemaVersion(
            String eventType, String producer, UUID eventId, String schemaVersion);

    @Modifying
    @Query(value = "insert into planning.planning_request "
            + "(id,event_type,producer,event_id,schema_version,status,attempt_count,next_attempt_at,created_at,updated_at,version) "
            + "values (:id,:eventType,:producer,:eventId,:schemaVersion,'PENDING',0,:now,:now,:now,0) "
            + "on conflict do nothing", nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("eventType") String eventType,
            @Param("producer") String producer, @Param("eventId") UUID eventId,
            @Param("schemaVersion") String schemaVersion, @Param("now") Instant now);

    @Query(value = "select * from planning.planning_request where "
            + "((status in ('PENDING','FAILED_RETRYABLE') and next_attempt_at <= :now) "
            + "or (status = 'PROCESSING' and lease_until <= :now)) "
            + "order by created_at limit :batchSize for update skip locked", nativeQuery = true)
    List<DiagnosisRequest> findClaimable(@Param("now") Instant now, @Param("batchSize") int batchSize);
}
