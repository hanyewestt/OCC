package com.csd.occ.planning.domain.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
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
@AutoConfigureMockMvc
@Import(ApplicationAuditRetentionWindowIT.FixedClockConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class ApplicationAuditRetentionWindowIT {
    private static final Instant NOW = Instant.parse("2026-10-25T02:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("occ")
            .withUsername("occ_admin")
            .withPassword("synthetic-admin")
            .withInitScript("db/create-planning-schema.sql");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("OCC_PLANNING_DB_INTERNAL_URL", POSTGRES::getJdbcUrl);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;

    @BeforeEach
    void clearAuditEvents() {
        jdbc.update("DELETE FROM planning.application_audit_event");
    }

    @Test
    void defaultWindowIs24HoursAndExplicitSearchHonorsInclusiveRetentionCutoff() throws Exception {
        UUID oneHour = insertEvent(NOW.minus(Duration.ofHours(1)));
        UUID twentyFiveHours = insertEvent(NOW.minus(Duration.ofHours(25)));
        UUID exactlyNinetyDays = insertEvent(NOW.minus(Duration.ofDays(90)));
        UUID ninetyOneDays = insertEvent(NOW.minus(Duration.ofDays(91)));
        var admin = SecurityMockMvcRequestPostProcessors.jwt()
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

        var defaultResponse = mvc.perform(get("/api/v1/planning/admin/audit-events").with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(oneHour.toString()))
                .andReturn().getResponse().getContentAsString();
        assertThat(defaultResponse).doesNotContain(exactlyNinetyDays.toString());

        var retainedResponse = mvc.perform(get("/api/v1/planning/admin/audit-events")
                        .param("from", NOW.minus(Duration.ofDays(91)).toString())
                        .param("to", NOW.toString())
                        .with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].id").value(oneHour.toString()))
                .andExpect(jsonPath("$.items[2].id").value(exactlyNinetyDays.toString()))
                .andReturn().getResponse().getContentAsString();
        assertThat(retainedResponse).contains(twentyFiveHours.toString()).doesNotContain(ninetyOneDays.toString());
    }

    private UUID insertEvent(Instant occurredAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO planning.application_audit_event
                    (id, event_schema_version, occurred_at, recorded_at, category, event_type, outcome,
                     source_service, actor_subject, actor_type, entity_type, entity_id, summary, details,
                     source_key, backfilled)
                VALUES (?, 1, ?, ?, 'OPERATIONS', 'RETENTION_WINDOW_TEST', 'SUCCESS', 'PLANNING',
                        'retention-test', 'SYSTEM', 'TEST', ?, 'retention boundary fixture', '{}'::jsonb,
                        ?, false)
                """, id, Timestamp.from(occurredAt), Timestamp.from(NOW), id.toString(), "retention-test:" + id);
        return id;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedAuditClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }
}
