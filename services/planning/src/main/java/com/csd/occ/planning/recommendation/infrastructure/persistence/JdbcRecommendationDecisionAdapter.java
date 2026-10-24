package com.csd.occ.planning.recommendation.infrastructure.persistence;

import com.csd.occ.planning.recommendation.domain.ControllerBusInstruction;
import com.csd.occ.planning.recommendation.domain.RecommendationDecision;
import com.csd.occ.planning.recommendation.domain.RecommendationDecisionAction;
import com.csd.occ.planning.recommendation.domain.RecommendationDecisionPort;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcRecommendationDecisionAdapter implements RecommendationDecisionPort {
    private static final String FIND = """
            SELECT recommendation_id, action, actor_subject, override_instructions,
                   controller_rationale, decided_at
              FROM planning.recommendation_decision
             WHERE recommendation_id = ?
            """;
    private static final String FIND_ALL = """
            SELECT recommendation_id, action, actor_subject, override_instructions,
                   controller_rationale, decided_at
              FROM planning.recommendation_decision
             ORDER BY decided_at DESC, recommendation_id
             OFFSET ? LIMIT ?
            """;
    private static final String INSERT = """
            INSERT INTO planning.recommendation_decision (
                recommendation_id, action, actor_subject, override_instructions,
                controller_rationale, decided_at
            ) VALUES (?, ?, ?, CAST(? AS JSONB), ?, ?)
            ON CONFLICT (recommendation_id) DO NOTHING
            """;

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public JdbcRecommendationDecisionAdapter(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<RecommendationDecision> findByRecommendationId(UUID recommendationId) {
        return jdbc.query(FIND, this::mapDecision, recommendationId).stream().findFirst();
    }

    @Override
    public Optional<RecommendationDecision> recordIfAbsent(RecommendationDecision decision) {
        int inserted = jdbc.update(INSERT, decision.recommendationId(), decision.action().name(),
                decision.actorSubject(), json.writeValueAsString(decision.overrideInstructions()),
                decision.controllerRationale(), Timestamp.from(decision.decidedAt()));
        return inserted == 1 ? Optional.of(decision) : Optional.empty();
    }

    @Override
    public List<RecommendationDecision> findAll(int offset, int limit) {
        return jdbc.query(FIND_ALL, this::mapDecision, offset, limit);
    }

    @Override
    public long count() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM planning.recommendation_decision", Long.class);
        return count == null ? 0 : count;
    }

    private RecommendationDecision mapDecision(ResultSet result, int rowNumber) throws SQLException {
        return new RecommendationDecision(
                result.getObject("recommendation_id", UUID.class),
                RecommendationDecisionAction.valueOf(result.getString("action")),
                result.getString("actor_subject"),
                json.readValue(result.getString("override_instructions"),
                        new TypeReference<List<ControllerBusInstruction>>() {}),
                result.getString("controller_rationale"),
                result.getTimestamp("decided_at").toInstant());
    }
}
