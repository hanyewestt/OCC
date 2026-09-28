package com.csd.occ.diagnosis.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DiagnosisRequestRepository extends JpaRepository<DiagnosisRequest, UUID> {
    Optional<DiagnosisRequest> findByEventTypeAndProducerAndEventIdAndSchemaVersion(
            String eventType, String producer, UUID eventId, String schemaVersion);

    @Modifying
    @Query(value = "insert into diagnosis.diagnosis_request "
            + "(id,event_type,producer,event_id,schema_version,status,attempt_count,next_attempt_at,created_at,updated_at,version) "
            + "values (:id,:eventType,:producer,:eventId,:schemaVersion,'PENDING',0,:now,:now,:now,0) "
            + "on conflict do nothing", nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("eventType") String eventType,
            @Param("producer") String producer, @Param("eventId") UUID eventId,
            @Param("schemaVersion") String schemaVersion, @Param("now") Instant now);

    @Query(value = "select * from diagnosis.diagnosis_request where "
            + "((status in ('PENDING','FAILED_RETRYABLE') and next_attempt_at <= :now) "
            + "or (status = 'PROCESSING' and lease_until <= :now)) "
            + "order by created_at limit :batchSize for update skip locked", nativeQuery = true)
    List<DiagnosisRequest> findClaimable(@Param("now") Instant now, @Param("batchSize") int batchSize);
}
