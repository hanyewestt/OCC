package com.csd.occ.ingestion.infrastructure.audit;

import com.csd.occ.ingestion.domain.ApplicationAuditRetentionService;
import com.csd.occ.ingestion.domain.AuditEvidenceSanitizer;
import com.csd.occ.ingestion.infrastructure.config.AuditRetentionProperties;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Copies safely available retained legacy poll outcomes into the independent audit history in bounded transactions. */
@Service
public class ApplicationAuditBackfillService {
    private static final int BATCH_SIZE = 200;
    private static final String INSERT = """
            insert into ingestion.application_audit_event
                (id, event_schema_version, occurred_at, recorded_at, category, event_type, outcome,
                 source_service, actor_subject, actor_type, entity_type, entity_id, run_id, bus_id,
                 captain_id, correlation_id, summary, details, source_key, backfilled)
            values (?, 1, ?, ?, 'OPERATIONS', 'FEED_POLL_COMPLETED', ?, 'INGESTION', null, 'SYSTEM',
                    'POLL_RUN', ?, ?, null, null, ?, 'Retained poll outcome backfilled', ?::jsonb, ?, true)
            on conflict (source_key) do nothing
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ApplicationAuditRetentionService retention;
    private final AuditRetentionProperties properties;
    private final Clock clock;
    private final JsonMapper json;

    public ApplicationAuditBackfillService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            ApplicationAuditRetentionService retention, AuditRetentionProperties properties, Clock clock,
            JsonMapper json) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.retention = retention;
        this.properties = properties;
        this.clock = clock;
        this.json = json;
    }

    public int backfillRetainedRecords() {
        retention.collectionStartedAt();
        Instant cutoff = clock.instant().minus(properties.retention());
        Instant cursorAt = null;
        UUID cursorId = null;
        int copied = 0;
        while (true) {
            Instant batchCursorAt = cursorAt;
            UUID batchCursorId = cursorId;
            Batch batch = transactions.execute(status -> {
                List<PollSnapshot> records = readBatch(cutoff, batchCursorAt, batchCursorId);
                Instant recordedAt = clock.instant();
                int inserted = 0;
                for (PollSnapshot poll : records) inserted += insert(poll, recordedAt);
                return new Batch(records, inserted);
            });
            if (batch == null || batch.records().isEmpty()) break;
            copied += batch.inserted();
            PollSnapshot last = batch.records().getLast();
            cursorAt = last.completedAt();
            cursorId = last.id();
            if (batch.records().size() < BATCH_SIZE) break;
        }
        return copied;
    }

    private List<PollSnapshot> readBatch(Instant cutoff, Instant cursorAt, UUID cursorId) {
        String sql = """
                select id, source, scope_key, completed_at, outcome, records_received, classification, http_status,
                       demo_run_id, route_position_poll_cycle_id
                from ingestion.poll_run
                where completed_at is not null and completed_at >= ?
                """ + (cursorAt == null ? "" : " and (completed_at > ? or (completed_at = ? and id > ?)) ")
                + " order by completed_at, id limit ?";
        if (cursorAt == null) {
            return jdbc.query(sql, this::snapshot, Timestamp.from(cutoff), BATCH_SIZE);
        }
        return jdbc.query(sql, this::snapshot, Timestamp.from(cutoff), Timestamp.from(cursorAt),
                Timestamp.from(cursorAt), cursorId, BATCH_SIZE);
    }

    private PollSnapshot snapshot(ResultSet row, int ignored) throws SQLException {
        return new PollSnapshot(row.getObject("id", UUID.class), row.getString("source"), row.getString("scope_key"),
                row.getTimestamp("completed_at").toInstant(), row.getString("outcome"),
                row.getInt("records_received"), row.getString("classification"),
                (Integer) row.getObject("http_status"), row.getObject("demo_run_id", UUID.class),
                row.getObject("route_position_poll_cycle_id", UUID.class));
    }

    private int insert(PollSnapshot poll, Instant recordedAt) {
        String sourceKey = "ingestion-poll:" + poll.id();
        UUID eventId = UUID.nameUUIDFromBytes(sourceKey.getBytes(StandardCharsets.UTF_8));
        var details = new LinkedHashMap<String, Object>();
        details.put("source", safeLabel(poll.source()));
        details.put("scope", safeScope(poll.scope()));
        details.put("pollOutcome", safeOutcome(poll.outcome()));
        details.put("recordsReceived", poll.recordsReceived());
        details.put("classification", safeClassification(poll.classification()));
        details.put("httpStatus", poll.httpStatus());
        String safeJson = json.writeValueAsString(AuditEvidenceSanitizer.sanitize(details));
        return jdbc.update(INSERT, eventId, Timestamp.from(poll.completedAt()), Timestamp.from(recordedAt),
                safeOutcome(poll.outcome()), poll.id().toString(), poll.demoRunId(),
                poll.routePositionPollCycleId() == null ? null : poll.routePositionPollCycleId().toString(),
                safeJson, sourceKey);
    }

    private static String safeOutcome(String value) {
        return switch (value == null ? "" : value) {
            case "SUCCESS" -> "SUCCESS";
            case "PARTIAL" -> "PARTIAL";
            case "FAILED" -> "FAILURE";
            default -> "UNKNOWN";
        };
    }

    private static String safeLabel(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,40}") ? value : "UNKNOWN";
    }

    private static String safeScope(String value) {
        return value != null && value.matches("[A-Za-z0-9:_-]{1,120}") ? value : "UNKNOWN";
    }

    private static String safeClassification(String value) {
        return "SIMULATION".equals(value) || "OBSERVATION_ONLY".equals(value) ? value : "UNKNOWN";
    }

    private record PollSnapshot(UUID id, String source, String scope, Instant completedAt, String outcome, int recordsReceived,
            String classification, Integer httpStatus, UUID demoRunId, UUID routePositionPollCycleId) { }
    private record Batch(List<PollSnapshot> records, int inserted) { }
}
