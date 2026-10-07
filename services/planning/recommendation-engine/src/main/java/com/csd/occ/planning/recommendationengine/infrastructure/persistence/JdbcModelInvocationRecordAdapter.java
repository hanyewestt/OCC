package com.csd.occ.planning.recommendationengine.infrastructure.persistence;

import com.csd.occ.planning.recommendationengine.domain.ModelInvocationRecord;
import com.csd.occ.planning.recommendationengine.domain.ModelInvocationRecordPort;
import com.csd.occ.planning.recommendationengine.domain.ModelTokenUsage;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcModelInvocationRecordAdapter implements ModelInvocationRecordPort {

    private static final String INSERT = """
            INSERT INTO recommendation.model_invocation_record (
                invocation_id, recommendation_id, provider, model, prompt_version,
                output_schema_version, provider_response_id, outcome, candidate_payload,
                rejection_reason, failure_category, deterministic_fallback_used,
                prompt_tokens, completion_tokens, total_tokens, started_at,
                completed_at, latency_milliseconds
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?, ?, ?, ?, ?, ?, ?, ?, ?
            )
            """;

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public JdbcModelInvocationRecordAdapter(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void save(ModelInvocationRecord record) {
        ModelTokenUsage usage = record.metadata().tokenUsage().orElse(null);
        jdbc.update(
                INSERT,
                record.metadata().invocationId(),
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
                record.metadata().latencyMilliseconds());
    }
}
