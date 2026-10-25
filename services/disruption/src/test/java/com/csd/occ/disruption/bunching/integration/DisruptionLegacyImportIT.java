package com.csd.occ.disruption.bunching.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class DisruptionLegacyImportIT {
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID RAW_OBSERVATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID POLL_RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID DISRUPTION_ID = UUID.fromString("00000000-0000-0000-0000-000000000007");
    private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000006");
    private static final UUID PROCESSING_STATE_ID = UUID.fromString("00000000-0000-0000-0000-000000000008");
    private static final UUID OUTBOX_ID = UUID.fromString("00000000-0000-0000-0000-000000000009");
    private static final Instant NOW = Instant.parse("2026-10-14T10:00:00Z");

    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:18.6-alpine"));

    @Test
    void importsLegacyOperationalStateAndEventHistoryIntoDisruption() {
        var dataSource = new DriverManagerDataSource(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE SCHEMA bunching");

        Flyway.configure().dataSource(dataSource).defaultSchema("bunching").schemas("bunching")
                .locations("classpath:db/legacy-bunching-migration").load().migrate();
        seedLegacyRows(jdbc);

        Flyway target = Flyway.configure().dataSource(dataSource).defaultSchema("disruption").schemas("disruption")
                .createSchemas(true).locations("classpath:db/migration").load();
        assertThat(target.migrate().migrationsExecuted).isEqualTo(8);

        assertThat(jdbc.queryForObject("SELECT last_sequence FROM disruption.consumer_state WHERE id=1", Long.class))
                .isEqualTo(42L);
        assertThat(jdbc.queryForObject("SELECT mode FROM disruption.consumer_state WHERE id=1", String.class))
                .isEqualTo("DEMO");
        assertThat(jdbc.queryForObject("SELECT demo_run_id FROM disruption.consumer_state WHERE id=1", UUID.class))
                .isEqualTo(RUN_ID);
        assertThat(jdbc.queryForObject("SELECT status FROM disruption.source_processing_state WHERE raw_observation_id=?",
                String.class, RAW_OBSERVATION_ID)).isEqualTo("PROCESSED");
        assertThat(jdbc.queryForObject("SELECT source_sequence FROM disruption.source_processing_state WHERE raw_observation_id=?",
                Long.class, RAW_OBSERVATION_ID)).isEqualTo(37L);

        assertThat(jdbc.queryForObject("SELECT kind FROM disruption.disruption WHERE id=?", String.class, DISRUPTION_ID))
                .isEqualTo("BUNCHING");
        assertThat(jdbc.queryForObject("SELECT status FROM disruption.disruption WHERE id=?", String.class, DISRUPTION_ID))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM disruption.disruption_event WHERE disruption_id=?",
                Integer.class, DISRUPTION_ID)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT kind FROM disruption.disruption_event WHERE id=?", String.class, EVENT_ID))
                .isEqualTo("BUNCHING");
        assertThat(jdbc.queryForObject("SELECT expected_headway_seconds FROM disruption.disruption_event WHERE id=?",
                Integer.class, EVENT_ID)).isEqualTo(600);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM disruption.event_evidence WHERE event_id=?",
                Integer.class, EVENT_ID)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT pending_headway_seconds FROM disruption.disruption_processing_state WHERE id=?",
                Integer.class, PROCESSING_STATE_ID)).isEqualTo(360);
        assertThat(jdbc.queryForObject("SELECT active_disruption_id FROM disruption.disruption_processing_state WHERE id=?",
                UUID.class, PROCESSING_STATE_ID)).isEqualTo(DISRUPTION_ID);

        assertThat(jdbc.queryForObject("SELECT status FROM disruption.planning_outbox WHERE id=?", String.class, OUTBOX_ID))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM disruption.planning_outbox WHERE id=?",
                Integer.class, OUTBOX_ID)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT payload_json FROM disruption.planning_outbox WHERE id=?",
                String.class, OUTBOX_ID)).contains("BUS_BUNCHING", EVENT_ID.toString(), "eventSnapshot",
                "planning-event-v1", "bunching-gap-v1");
        assertThat(jdbc.queryForObject("SELECT schema_version FROM disruption.planning_outbox WHERE id=?",
                String.class, OUTBOX_ID)).isEqualTo("planning-event-v1");
        assertThat(jdbc.queryForObject("SELECT payload_fingerprint FROM disruption.planning_outbox WHERE id=?",
                String.class, OUTBOX_ID)).isNull();

        assertThat(jdbc.queryForObject("""
                SELECT ledger.source_row_hash = encode(sha256(convert_to(to_jsonb(source)::text, 'UTF8')), 'hex')
                  FROM disruption.legacy_import_ledger ledger
                  JOIN bunching.source_processing_state source
                    ON source.raw_observation_id::text = ledger.source_primary_key
                 WHERE ledger.source_table = 'source_processing_state'
                   AND ledger.source_primary_key = ?
                """, Boolean.class, RAW_OBSERVATION_ID.toString())).isTrue();
        assertThat(target.migrate().migrationsExecuted).isZero();
    }

    private static void seedLegacyRows(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO bunching.source_processing_state
                    (raw_observation_id, status, processed_at, demo_run_id, evaluated_at,
                     threshold_seconds, bunched_group_count, source_sequence, version)
                VALUES (?, 'PROCESSED', ?, ?, ?, 90, 1, 37, 4)
                """, RAW_OBSERVATION_ID, Timestamp.from(NOW), RUN_ID, Timestamp.from(NOW));
        jdbc.update("UPDATE bunching.consumer_state SET mode='DEMO', demo_run_id=?, last_sequence=42, updated_at=?, version=9 WHERE id=1",
                RUN_ID, Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO bunching.alert_event
                    (id, alert_id, action, classification, service_id, stop_code, direction_id,
                     raw_observation_id, poll_run_id, fetched_at, triggered_at, evidence, demo_run_id,
                     expected_headway_seconds, first_observed_headway_seconds, second_observed_headway_seconds,
                     first_confirming_at, second_confirming_at, first_leading_observation_id,
                     first_following_observation_id, second_leading_observation_id,
                     second_following_observation_id, detection_rule_version)
                VALUES (?, ?, 'OPENED', 'SIMULATION', '190', '44009', 1, ?, ?, ?, ?,
                        '{"snapshot":{"version":1}}'::jsonb, ?, 600, 360, 390, ?, ?, ?, ?, ?, ?, 'bunching-gap-v1')
                """, EVENT_ID, DISRUPTION_ID, RAW_OBSERVATION_ID, POLL_RUN_ID,
                Timestamp.from(NOW), Timestamp.from(NOW), RUN_ID, Timestamp.from(NOW.minusSeconds(5)),
                Timestamp.from(NOW), UUID.fromString("00000000-0000-0000-0000-000000000011"),
                UUID.fromString("00000000-0000-0000-0000-000000000012"),
                UUID.fromString("00000000-0000-0000-0000-000000000013"),
                UUID.fromString("00000000-0000-0000-0000-000000000014"));
        jdbc.update("""
                INSERT INTO bunching.alert_processing_state
                    (id, demo_run_id, service_id, stop_code, direction_id, last_fetched_at, active_alert_id,
                     pending_headway_seconds, pending_detected_at, pending_raw_observation_id, pending_poll_run_id)
                VALUES (?, ?, '190', '44009', 1, ?, ?, 360, ?, ?, ?)
                """, PROCESSING_STATE_ID, RUN_ID, Timestamp.from(NOW), DISRUPTION_ID,
                Timestamp.from(NOW.minusSeconds(5)), RAW_OBSERVATION_ID, POLL_RUN_ID);
        jdbc.update("""
                INSERT INTO bunching.diagnosis_outbox
                    (id, event_id, status, attempt_count, next_attempt_at, created_at)
                VALUES (?, ?, 'PENDING', 2, ?, ?)
                """, OUTBOX_ID, EVENT_ID, Timestamp.from(NOW), Timestamp.from(NOW.minusSeconds(30)));
    }
}
