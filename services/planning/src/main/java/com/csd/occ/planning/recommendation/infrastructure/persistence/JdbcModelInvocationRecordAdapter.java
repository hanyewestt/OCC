package com.csd.occ.planning.recommendation.infrastructure.persistence;

import com.csd.occ.planning.recommendation.domain.ModelInvocationRecord;
import com.csd.occ.planning.recommendation.domain.ModelInvocationRecordPort;
import com.csd.occ.planning.recommendation.domain.ModelTokenUsage;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcModelInvocationRecordAdapter implements ModelInvocationRecordPort {

    private static final String INSERT = """
            INSERT INTO planning.model_invocation_record (
                id, purpose, recommendation_id, correlation_id, provider, model,
                prompt_version, output_schema_version, provider_response_id, outcome,
                candidate_payload, rejection_reason, failure_category, deterministic_fallback_used,
                prompt_tokens, completion_tokens, total_tokens, started_at, completed_at,
                latency_millis, input_snapshot, generated_response
            ) VALUES (
                ?, 'RECOMMENDATION', ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
            )
            """;

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public JdbcModelInvocationRecordAdapter(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void save(ModelInvocationRecord record) {
        ModelTokenUsage usage = record.metadata().tokenUsage().orElse(null);
        jdbc.update(
                INSERT,
                record.metadata().invocationId(),
                jdbc.queryForObject("select exists(select 1 from planning.recommendation where recommendation_id = ?)", Boolean.class, record.recommendationId()) ? record.recommendationId() : null,
                record.recommendationId(),
                record.metadata().provider().name(),
                record.metadata().model(),
                record.metadata().promptVersion(),
                record.metadata().outputSchemaVersion(),
                record.metadata().providerResponseId().orElse(null),
                record.outcome().name(),
                record.candidate().map(json::writeValueAsString).orElse(null),
                record.rejectionReason().map(Enum::name).orElse(null),
                record.failureCategory().map(Enum::name).orElse(null),
                record.deterministicFallbackUsed(),
                usage == null ? null : usage.promptTokens().orElse(null),
                usage == null ? null : usage.completionTokens().orElse(null),
                usage == null ? null : usage.totalTokens().orElse(null),
                Timestamp.from(record.metadata().startedAt()),
                Timestamp.from(record.metadata().completedAt()),
                record.metadata().latencyMilliseconds(),
                record.metadata().inputSnapshot(), record.metadata().generatedResponse());
    }

    @Override
    public void attach(java.util.UUID recommendationId) {
        jdbc.update("update planning.model_invocation_record set recommendation_id = ? where correlation_id = ? and recommendation_id is null", recommendationId, recommendationId);
    }
}
