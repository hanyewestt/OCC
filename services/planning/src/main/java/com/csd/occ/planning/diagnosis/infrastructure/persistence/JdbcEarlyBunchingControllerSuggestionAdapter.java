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

    private EarlyBunchingControllerSuggestion map(ResultSet result, int row) throws SQLException {
        return new EarlyBunchingControllerSuggestion(result.getObject("request_id", UUID.class),
                json.readValue(result.getString("instructions"), new TypeReference<List<ControllerBusInstruction>>() {}),
                result.getString("controller_rationale"), result.getString("actor_subject"), result.getTimestamp("suggested_at").toInstant());
    }
}
