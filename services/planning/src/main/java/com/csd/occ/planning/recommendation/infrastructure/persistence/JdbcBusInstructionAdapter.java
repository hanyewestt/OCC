package com.csd.occ.planning.recommendation.infrastructure.persistence;

import com.csd.occ.planning.recommendation.domain.BusInstruction;
import com.csd.occ.planning.recommendation.domain.BusInstructionPort;
import com.csd.occ.planning.recommendation.domain.BusInstructionStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcBusInstructionAdapter implements BusInstructionPort {
    private static final String INSERT = """
            INSERT INTO planning.bus_instruction (
                instruction_id, recommendation_id, bus_reference, instruction,
                status, sent_at, acknowledged_at, acknowledged_by
            ) VALUES (?, ?, ?, ?, ?, ?, NULL, NULL)
            ON CONFLICT (recommendation_id, bus_reference) DO NOTHING
            """;
    private static final String FIND_BY_BUS_REFERENCE = """
            SELECT instruction_id, recommendation_id, bus_reference, instruction,
                   status, sent_at, acknowledged_at, acknowledged_by
              FROM planning.bus_instruction
             WHERE bus_reference = ?
             ORDER BY sent_at DESC, instruction_id
             OFFSET ? LIMIT ?
            """;
    private static final String FIND_BY_ID = """
            SELECT instruction_id, recommendation_id, bus_reference, instruction,
                   status, sent_at, acknowledged_at, acknowledged_by
              FROM planning.bus_instruction
             WHERE instruction_id = ?
            """;
    private static final String ACKNOWLEDGE_IF_SENT = """
            UPDATE planning.bus_instruction
               SET status = ?, acknowledged_at = ?, acknowledged_by = ?
             WHERE instruction_id = ?
               AND status = ?
            """;
    private static final String COUNT_BY_BUS_REFERENCE = """
            SELECT count(*)
              FROM planning.bus_instruction
             WHERE bus_reference = ?
            """;

    private final JdbcTemplate jdbc;

    public JdbcBusInstructionAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean saveIfAbsent(BusInstruction instruction) {
        int inserted = jdbc.update(INSERT,
                instruction.instructionId(),
                instruction.recommendationId(),
                instruction.busReference(),
                instruction.instruction(),
                instruction.status().name(),
                Timestamp.from(instruction.sentAt()));
        return inserted == 1;
    }

    @Override
    public Optional<BusInstruction> findById(UUID instructionId) {
        return jdbc.query(FIND_BY_ID, this::mapInstruction, instructionId).stream().findFirst();
    }

    @Override
    public boolean acknowledgeIfSent(BusInstruction instruction) {
        int updated = jdbc.update(ACKNOWLEDGE_IF_SENT,
                instruction.status().name(),
                Timestamp.from(instruction.acknowledgedAt().orElseThrow()),
                instruction.acknowledgedBy().orElseThrow(),
                instruction.instructionId(),
                BusInstructionStatus.SENT.name());
        return updated == 1;
    }

    @Override
    public List<BusInstruction> findByBusReference(String busReference, int offset, int limit) {
        return jdbc.query(FIND_BY_BUS_REFERENCE, this::mapInstruction, busReference, offset, limit);
    }

    @Override
    public long countByBusReference(String busReference) {
        Long count = jdbc.queryForObject(COUNT_BY_BUS_REFERENCE, Long.class, busReference);
        return count == null ? 0 : count;
    }

    private BusInstruction mapInstruction(ResultSet result, int rowNumber) throws SQLException {
        Timestamp acknowledgedAt = result.getTimestamp("acknowledged_at");
        return new BusInstruction(
                result.getObject("instruction_id", UUID.class),
                result.getObject("recommendation_id", UUID.class),
                result.getString("bus_reference"),
                result.getString("instruction"),
                BusInstructionStatus.valueOf(result.getString("status")),
                result.getTimestamp("sent_at").toInstant(),
                acknowledgedAt == null ? Optional.empty() : Optional.of(acknowledgedAt.toInstant()),
                Optional.ofNullable(result.getString("acknowledged_by")));
    }
}
