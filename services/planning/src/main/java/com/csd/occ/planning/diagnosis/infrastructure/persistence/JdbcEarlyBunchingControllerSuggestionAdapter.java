package com.csd.occ.planning.diagnosis.infrastructure.persistence;

import com.csd.occ.planning.diagnosis.domain.EarlyBunchingControllerSuggestion;
import com.csd.occ.planning.diagnosis.domain.EarlyBunchingControllerSuggestionPort;
import com.csd.occ.planning.recommendation.domain.ControllerBusInstruction;
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
public class JdbcEarlyBunchingControllerSuggestionAdapter implements EarlyBunchingControllerSuggestionPort {
    private static final String FIND = "SELECT request_id, instructions, controller_rationale, actor_subject, suggested_at FROM planning.early_bunching_controller_suggestion WHERE request_id = ?";
    private static final String INSERT = "INSERT INTO planning.early_bunching_controller_suggestion (request_id, instructions, controller_rationale, actor_subject, suggested_at) VALUES (?, CAST(? AS JSONB), ?, ?, ?) ON CONFLICT (request_id) DO NOTHING";
    private static final String FIND_ALL = """
            SELECT suggestion.request_id, suggestion.instructions, suggestion.controller_rationale, suggestion.actor_subject, suggestion.suggested_at
              FROM planning.early_bunching_controller_suggestion suggestion
              JOIN planning.planning_request request ON request.id = suggestion.request_id
             WHERE cast(request.input_snapshot as jsonb) -> 'event' ->> 'classification' = ?
               AND coalesce(cast(request.input_snapshot as jsonb) -> 'event' ->> 'demoRunId', '') = ?
             ORDER BY suggestion.suggested_at DESC, suggestion.request_id
             OFFSET ? LIMIT ?
            """;
    private static final String COUNT = """
            SELECT count(*) FROM planning.early_bunching_controller_suggestion suggestion
              JOIN planning.planning_request request ON request.id = suggestion.request_id
             WHERE cast(request.input_snapshot as jsonb) -> 'event' ->> 'classification' = ?
               AND coalesce(cast(request.input_snapshot as jsonb) -> 'event' ->> 'demoRunId', '') = ?
            """;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public JdbcEarlyBunchingControllerSuggestionAdapter(JdbcTemplate jdbc, JsonMapper json) { this.jdbc = jdbc; this.json = json; }

    @Override public Optional<EarlyBunchingControllerSuggestion> findByRequestId(UUID requestId) {
        return jdbc.query(FIND, this::map, requestId).stream().findFirst();
    }

    @Override public Optional<EarlyBunchingControllerSuggestion> recordIfAbsent(EarlyBunchingControllerSuggestion suggestion) {
        int inserted = jdbc.update(INSERT, suggestion.requestId(), json.writeValueAsString(suggestion.instructions()),
                suggestion.controllerRationale(), suggestion.actorSubject(), Timestamp.from(suggestion.suggestedAt()));
        return inserted == 1 ? Optional.of(suggestion) : Optional.empty();
    }

    @Override public List<EarlyBunchingControllerSuggestion> findAll(String classification, UUID demoRunId, int offset, int limit) {
        return jdbc.query(FIND_ALL, this::map, classification, demoRunId == null ? "" : demoRunId.toString(), offset, limit);
    }

    @Override public long count(String classification, UUID demoRunId) {
        Long count = jdbc.queryForObject(COUNT, Long.class, classification, demoRunId == null ? "" : demoRunId.toString());
        return count == null ? 0 : count;
    }

    private EarlyBunchingControllerSuggestion map(ResultSet result, int row) throws SQLException {
        return new EarlyBunchingControllerSuggestion(result.getObject("request_id", UUID.class),
                json.readValue(result.getString("instructions"), new TypeReference<List<ControllerBusInstruction>>() {}),
                result.getString("controller_rationale"), result.getString("actor_subject"), result.getTimestamp("suggested_at").toInstant());
    }
}
