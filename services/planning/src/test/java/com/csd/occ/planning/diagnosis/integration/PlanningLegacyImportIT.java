package com.csd.occ.planning.diagnosis.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {
        "OCC_PLANNING_DB_USERNAME=occ_planning",
        "OCC_PLANNING_DB_PASSWORD=synthetic-planning",
        "OCC_PLANNING_DISRUPTION_PEER_TOKEN=synthetic-peer-token",
        "OCC_DISRUPTION_INTERNAL_URL=http://127.0.0.1:1",
        "OCC_INGESTION_INTERNAL_URL=http://127.0.0.1:1",
        "OCC_PLANNING_DIAGNOSIS_WORKER_ENABLED=false",
        "OCC_PLANNING_GEMINI_API_KEY=test-no-network",
        "SUPABASE_PROJECT_URL=https://example.supabase.co",
        "SUPABASE_JWKS_URL=https://example.supabase.co/auth/v1/.well-known/jwks.json"
})
@Testcontainers(disabledWithoutDocker = true)
class PlanningLegacyImportIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("occ")
            .withUsername("occ_admin")
            .withPassword("synthetic-admin")
            .withInitScript("db/create-planning-legacy-fixture.sql");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("OCC_PLANNING_DB_INTERNAL_URL", POSTGRES::getJdbcUrl);
    }

    @Autowired private JdbcTemplate jdbc;

    @Test
    void importsPendingWorkResultsAndEvidenceWithoutTruncationOrIdCollision() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM planning.planning_request", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM planning.diagnosis_result", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM planning.recommendation_job", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM planning.recommendation", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM planning.model_invocation_record", Integer.class)).isEqualTo(2);

        assertThat(jdbc.queryForMap("""
                SELECT status, attempt_count, version, input_snapshot
                  FROM planning.planning_request
                 WHERE id = '00000000-0000-0000-0000-000000000001'
                """))
                .containsEntry("status", "PENDING")
                .containsEntry("attempt_count", 2)
                .containsEntry("version", 7L)
                .containsEntry("input_snapshot", "{\"captured\":true}");
        assertThat(jdbc.queryForMap("SELECT producer, schema_version FROM planning.planning_request WHERE id = '00000000-0000-0000-0000-000000000001'"))
                .containsEntry("producer", "disruption")
                .containsEntry("schema_version", "planning-event-v1");
        assertThat(jdbc.queryForObject("SELECT length(model) FROM planning.diagnosis_result", Integer.class)).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT length(model) FROM planning.model_invocation_record WHERE purpose = 'RECOMMENDATION'", Integer.class)).isEqualTo(128);
        assertThat(jdbc.queryForObject("SELECT length(provider_response_id) FROM planning.model_invocation_record WHERE purpose = 'RECOMMENDATION'", Integer.class)).isEqualTo(255);
        assertThat(jdbc.queryForObject("SELECT input_snapshot FROM planning.model_invocation_record WHERE purpose = 'DIAGNOSIS'", String.class))
                .isEqualTo("{\"input\":\"kept\"}");

        UUID diagnosisInvocationId = UUID.fromString("f0000000-0000-0000-0000-000000000001");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM planning.model_invocation_record
                 WHERE id = ? AND purpose = 'DIAGNOSIS'
                """, Integer.class, diagnosisInvocationId)).isEqualTo(1);
        UUID recommendationInvocationId = jdbc.queryForObject("""
                SELECT id FROM planning.model_invocation_record WHERE purpose = 'RECOMMENDATION'
                """, UUID.class);
        assertThat(recommendationInvocationId).isNotEqualTo(diagnosisInvocationId);
        assertThat(jdbc.queryForObject("""
                SELECT target_id FROM planning.legacy_import_ledger
                 WHERE source_schema = 'recommendation' AND source_table = 'model_invocation_record'
                """, UUID.class)).isEqualTo(recommendationInvocationId);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM planning.legacy_import_ledger
                 WHERE source_row_hash !~ '^[0-9a-f]{64}$' OR status <> 'APPLIED'
                """, Integer.class)).isZero();
    }
}
