package com.csd.occ.planning.recommendation.infrastructure.persistence;

import com.csd.occ.planning.recommendation.domain.RecommendationDecision;
import com.csd.occ.planning.recommendation.domain.RecommendationDecisionAction;
import com.csd.occ.planning.recommendation.domain.RecommendationDecisionPort;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRecommendationDecisionAdapter implements RecommendationDecisionPort {
    private static final String FIND = """
            SELECT recommendation_id, action, actor_subject, decided_at
              FROM planning.recommendation_decision
             WHERE recommendation_id = ?
            """;
    private static final String INSERT = """
            INSERT INTO planning.recommendation_decision (recommendation_id, action, actor_subject, decided_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (recommendation_id) DO NOTHING
            """;

    private final JdbcTemplate jdbc;

    public JdbcRecommendationDecisionAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<RecommendationDecision> findByRecommendationId(UUID recommendationId) {
        return jdbc.query(FIND, this::mapDecision, recommendationId).stream().findFirst();
    }

    @Override
    public Optional<RecommendationDecision> recordIfAbsent(RecommendationDecision decision) {
        int inserted = jdbc.update(INSERT, decision.recommendationId(), decision.action().name(),
                decision.actorSubject(), Timestamp.from(decision.decidedAt()));
        return inserted == 1 ? Optional.of(decision) : Optional.empty();
    }

    private RecommendationDecision mapDecision(ResultSet result, int rowNumber) throws SQLException {
        return new RecommendationDecision(
                result.getObject("recommendation_id", UUID.class),
                RecommendationDecisionAction.valueOf(result.getString("action")),
                result.getString("actor_subject"),
                result.getTimestamp("decided_at").toInstant());
    }
}
