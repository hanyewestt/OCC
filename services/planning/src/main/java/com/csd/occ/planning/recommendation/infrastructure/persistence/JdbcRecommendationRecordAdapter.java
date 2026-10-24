package com.csd.occ.planning.recommendation.infrastructure.persistence;

import com.csd.occ.planning.recommendation.domain.BunchingRootCauseDiagnosis;
import com.csd.occ.planning.recommendation.domain.ConfirmedBunchingEvent;
import com.csd.occ.planning.recommendation.domain.RecommendationRecord;
import com.csd.occ.planning.recommendation.domain.RecommendationRecordPort;
import com.csd.occ.planning.recommendation.domain.SourceClassification;
import com.csd.occ.planning.recommendation.domain.ValidatedMitigationContext;
import com.csd.occ.planning.recommendation.domain.ValidatedMitigationRecommendation;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcRecommendationRecordAdapter implements RecommendationRecordPort {

    private static final String FIND_BY_IDEMPOTENCY_KEY = """
            SELECT recommendation_id, request_hash, request_payload,
                   mitigation_context, response_payload, created_at
              FROM planning.recommendation
             WHERE recommendation_id = ?
            """;

    private static final String FIND_BY_BUNCHING_EVENT_ID = """
            SELECT recommendation_id, request_hash, request_payload,
                   mitigation_context, response_payload, created_at
              FROM planning.recommendation
             WHERE bunching_event_id = ?
             ORDER BY created_at DESC
             LIMIT 1
            """;

    private static final String FIND_PENDING = """
            SELECT recommendation.recommendation_id, recommendation.request_hash,
                   recommendation.request_payload, recommendation.mitigation_context,
                   recommendation.response_payload, recommendation.created_at
              FROM planning.recommendation recommendation
              LEFT JOIN planning.recommendation_decision decision
                ON decision.recommendation_id = recommendation.recommendation_id
             WHERE decision.recommendation_id IS NULL
               AND recommendation.request_payload ->> 'classification' = 'SIMULATION'
               AND recommendation.response_payload ->> 'status' = 'RECOMMENDED'
             ORDER BY recommendation.created_at, recommendation.recommendation_id
             OFFSET ? LIMIT ?
            """;

    private static final String COUNT_PENDING = """
            SELECT count(*)
              FROM planning.recommendation recommendation
              LEFT JOIN planning.recommendation_decision decision
                ON decision.recommendation_id = recommendation.recommendation_id
             WHERE decision.recommendation_id IS NULL
               AND recommendation.request_payload ->> 'classification' = 'SIMULATION'
               AND recommendation.response_payload ->> 'status' = 'RECOMMENDED'
            """;

    private static final String INSERT_IF_ABSENT = """
            INSERT INTO planning.recommendation (
                recommendation_id, request_hash, bunching_event_id, direction_id,
                adjustment_seconds, request_payload, mitigation_context,
                response_payload, created_at
            ) VALUES (
                ?, ?, ?, ?, ?, CAST(? AS JSONB), CAST(? AS JSONB),
                CAST(? AS JSONB), ?
            )
            ON CONFLICT (recommendation_id) DO NOTHING
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
    public List<RecommendationRecord> findPending(int offset, int limit) {
        return jdbc.query(FIND_PENDING, this::mapRecord, offset, limit);
    }

    @Override
    public long countPending() {
        Long count = jdbc.queryForObject(COUNT_PENDING, Long.class);
        return count == null ? 0 : count;
    }

    private static final String ACTIVE_RUN_FILTER = """
               AND EXISTS (SELECT 1 FROM planning.diagnosis_result diagnosis
                    WHERE diagnosis.id::text = recommendation.request_payload -> 'diagnosis' ->> 'diagnosisId'
                      AND diagnosis.evidence_snapshot -> 'event' ->> 'classification' = 'SIMULATION'
                      AND diagnosis.evidence_snapshot -> 'event' ->> 'demoRunId' = ?)
            """;

    @Override
    public List<RecommendationRecord> findPendingForRun(UUID demoRunId, int offset, int limit) {
        String query = FIND_PENDING.replace("ORDER BY", ACTIVE_RUN_FILTER + " ORDER BY");
        return jdbc.query(query, this::mapRecord, demoRunId.toString(), offset, limit);
    }

    @Override
    public long countPendingForRun(UUID demoRunId) {
        Long count = jdbc.queryForObject(COUNT_PENDING + ACTIVE_RUN_FILTER, Long.class, demoRunId.toString());
        return count == null ? 0 : count;
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
                record.requestHash(),
                record.bunchingEvent().bunchingEventId(),
                record.bunchingEvent().directionId(),
                recommendation.adjustmentSeconds(),
                json.writeValueAsString(request),
                json.writeValueAsString(record.mitigationContext()),
                json.writeValueAsString(recommendation),
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
                result.getObject("recommendation_id", UUID.class),
                result.getString("request_hash"),
                request.requestId(),
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
