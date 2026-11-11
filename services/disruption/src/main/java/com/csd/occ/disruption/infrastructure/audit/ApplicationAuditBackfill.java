package com.csd.occ.disruption.infrastructure.audit;

import com.csd.occ.disruption.domain.audit.ApplicationAuditRetentionService;
import com.csd.occ.disruption.domain.audit.AuditEvidenceSanitizer;
import com.csd.occ.disruption.infrastructure.config.AuditRetentionProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ApplicationAuditBackfill implements ApplicationRunner {
    private static final int BATCH_SIZE = 200;
    private static final Set<String> KINDS = Set.of("BUNCHING", "TRAFFIC_INCIDENT", "FLOOD", "RAIL_DISRUPTION", "ROADWORKS");
    private static final Set<String> ACTIONS = Set.of("OPENED", "UPDATED", "CLEARED");
    private static final Set<String> CLASSIFICATIONS = Set.of("OBSERVATION_ONLY", "SIMULATION");
    private static final String QUERY = """
            SELECT id, disruption_id, kind, action, classification, demo_run_id, triggered_at
            FROM disruption.disruption_event
            WHERE triggered_at >= ?
              AND (?::timestamptz IS NULL OR (triggered_at, id) > (?, ?::uuid))
            ORDER BY triggered_at, id
            LIMIT 200
            """;
    private static final String INSERT = """
            INSERT INTO disruption.application_audit_event
                (id, event_schema_version, occurred_at, recorded_at, category, event_type, outcome, source_service,
                 actor_subject, actor_type, entity_type, entity_id, run_id, summary, details, source_key, backfilled)
            VALUES (?, 1, ?, ?, 'OPERATIONS', 'DISRUPTION_TRANSITION', 'SUCCESS', 'DISRUPTION',
                    NULL, 'SYSTEM', 'DISRUPTION', ?, ?, ?, CAST(? AS jsonb), ?, TRUE)
            ON CONFLICT (source_key) DO NOTHING
            """;
    private final JdbcTemplate jdbc;
    private final ApplicationAuditRetentionService retention;
    private final AuditRetentionProperties retentionProperties;
    private final Clock clock;
    private final JsonMapper json;

    public ApplicationAuditBackfill(JdbcTemplate jdbc, ApplicationAuditRetentionService retention,
            AuditRetentionProperties retentionProperties, Clock clock, JsonMapper json) {
        this.jdbc = jdbc;
        this.retention = retention;
        this.retentionProperties = retentionProperties;
        this.clock = clock;
        this.json = json;
    }

    @Override
    public void run(ApplicationArguments args) {
        retention.collectionStartedAt();
        Instant cutoff = clock.instant().minus(retentionProperties.retention());
        Instant lastAt = null;
        UUID lastId = null;
        while (true) {
            List<Transition> batch = jdbc.query(QUERY, (rs, row) -> new Transition(rs.getObject("id", UUID.class),
                    rs.getObject("disruption_id", UUID.class), rs.getString("kind"), rs.getString("action"), rs.getString("classification"),
                    rs.getObject("demo_run_id", UUID.class), rs.getTimestamp("triggered_at").toInstant()),
                    java.sql.Timestamp.from(cutoff), lastAt, lastAt, lastId);
            if (batch.isEmpty()) return;
            jdbc.batchUpdate(INSERT, batch.stream().map(transition -> {
                String sourceKey = "disruption:" + transition.eventId();
                String kind = safe(transition.kind(), KINDS);
                String action = safe(transition.action(), ACTIONS);
                String classification = safe(transition.classification(), CLASSIFICATIONS);
                String details = json.writeValueAsString(AuditEvidenceSanitizer.sanitize(
                        Map.of("kind", kind, "action", action, "classification", classification)));
                return new Object[] { UUID.nameUUIDFromBytes(sourceKey.getBytes(StandardCharsets.UTF_8)),
                        java.sql.Timestamp.from(transition.occurredAt()), java.sql.Timestamp.from(clock.instant()),
                        transition.disruptionId().toString(), transition.runId(),
                        "Disruption " + kind + " " + action.toLowerCase(java.util.Locale.ROOT), details, sourceKey };
            }).toList());
            Transition last = batch.getLast();
            lastAt = last.occurredAt();
            lastId = last.eventId();
            if (batch.size() < BATCH_SIZE) return;
        }
    }

    private static String safe(String value, Set<String> allowed) {
        return allowed.contains(value) ? value : "UNKNOWN";
    }

    private record Transition(UUID eventId, UUID disruptionId, String kind, String action, String classification, UUID runId,
                              java.time.Instant occurredAt) { }
}
