package com.csd.occ.planning.recommendationengine.infrastructure.persistence;

import com.csd.occ.planning.recommendationengine.domain.BunchingRootCauseDiagnosis;
import com.csd.occ.planning.recommendationengine.domain.ConfirmedBunchingEvent;
import com.csd.occ.planning.recommendationengine.domain.RecommendationRecord;
import com.csd.occ.planning.recommendationengine.domain.RecommendationRecordPort;
import com.csd.occ.planning.recommendationengine.domain.SourceClassification;
import com.csd.occ.planning.recommendationengine.domain.ValidatedMitigationContext;
import com.csd.occ.planning.recommendationengine.domain.ValidatedMitigationRecommendation;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcRecommendationRecordAdapter implements RecommendationRecordPort {

    private static final String FIND_BY_IDEMPOTENCY_KEY = """
            SELECT idempotency_key, request_hash, request_id, request_payload,
                   mitigation_context, response_payload, created_at
              FROM recommendation.recommendation_record
             WHERE idempotency_key = ?
            """;

    private static final String FIND_BY_BUNCHING_EVENT_ID = """
            SELECT idempotency_key, request_hash, request_id, request_payload,
                   mitigation_context, response_payload, created_at
              FROM recommendation.recommendation_record
             WHERE bunching_event_id = ?
             ORDER BY created_at DESC
             LIMIT 1
            """;

    private static final String INSERT_IF_ABSENT = """
            INSERT INTO recommendation.recommendation_record (
                recommendation_id, idempotency_key, request_hash, request_id,
                bunching_event_id, diagnosis_id, classification, service_id,
                direction_id, monitored_stop_code, mitigation_type,
                recommendation_status, rationale, target_bus_reference,
                control_stop_code, adjustment_seconds, request_payload,
                mitigation_context, response_payload, evidence_ids, created_at
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                CAST(? AS JSONB), CAST(? AS JSONB), CAST(? AS JSONB),
                CAST(? AS JSONB), ?
            )
            ON CONFLICT (idempotency_key) DO NOTHING
            """;

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public JdbcRecommendationRecordAdapter(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<RecommendationRecord> findByIdempotencyKey(UUID idempotencyKey) {
        return jdbc.query(FIND_BY_IDEMPOTENCY_KEY, this::mapRecord, idempotencyKey)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<RecommendationRecord> findByBunchingEventId(UUID bunchingEventId) {
        return jdbc.query(FIND_BY_BUNCHING_EVENT_ID, this::mapRecord, bunchingEventId)
                .stream()
                .findFirst();
    }

    @Override
    public boolean saveIfAbsent(RecommendationRecord record) {
        PersistedRequest request = new PersistedRequest(
                record.requestId(),
                record.classification(),
                record.bunchingEvent(),
                record.diagnosis());
        ValidatedMitigationRecommendation recommendation = record.recommendation();

        int inserted = jdbc.update(
                INSERT_IF_ABSENT,
                recommendation.recommendationId(),
                record.idempotencyKey(),
                record.requestHash(),
                record.requestId(),
                record.bunchingEvent().bunchingEventId(),
                record.diagnosis().diagnosisId(),
                record.classification().name(),
                record.bunchingEvent().serviceId(),
                record.bunchingEvent().directionId(),
                record.bunchingEvent().monitoredStopCode(),
                recommendation.mitigationType().name(),
                recommendation.status().name(),
                recommendation.explanation().rationale(),
                recommendation.targetBusReference(),
                recommendation.controlStopCode(),
                recommendation.adjustmentSeconds(),
                json.writeValueAsString(request),
                json.writeValueAsString(record.mitigationContext()),
                json.writeValueAsString(recommendation),
                json.writeValueAsString(recommendation.evidenceIds()),
                Timestamp.from(record.createdAt()));
        return inserted == 1;
    }

    private RecommendationRecord mapRecord(ResultSet result, int rowNumber) throws SQLException {
        PersistedRequest request = json.readValue(result.getString("request_payload"), PersistedRequest.class);
        ValidatedMitigationContext context = json.readValue(
                result.getString("mitigation_context"),
                ValidatedMitigationContext.class);
        ValidatedMitigationRecommendation recommendation = json.readValue(
                result.getString("response_payload"),
                ValidatedMitigationRecommendation.class);
        return new RecommendationRecord(
                result.getObject("idempotency_key", UUID.class),
                result.getString("request_hash"),
                result.getObject("request_id", UUID.class),
                request.classification(),
                request.bunchingEvent(),
                request.diagnosis(),
                context,
                recommendation,
                result.getTimestamp("created_at").toInstant());
    }

    private record PersistedRequest(
            UUID requestId,
            SourceClassification classification,
            ConfirmedBunchingEvent bunchingEvent,
            BunchingRootCauseDiagnosis diagnosis) {
    }
}
