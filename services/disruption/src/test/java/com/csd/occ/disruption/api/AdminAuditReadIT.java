package com.csd.occ.disruption.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@SpringBootTest(properties = {"OCC_AUDIT_RETENTION=90d", "OCC_DISRUPTION_CONSUMER_ENABLED=false",
        "OCC_DISRUPTION_PLANNING_WORKER_ENABLED=false", "OCC_DISRUPTION_HAZARD_ENABLED=false",
        "OCC_DISRUPTION_TRAFFIC_POLL_ENABLED=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AdminAuditReadIT.Time.class)
@Testcontainers(disabledWithoutDocker = true)
class AdminAuditReadIT {
    private static final Instant NOW = Instant.parse("2026-10-25T02:00:00Z");
    private static final UUID FIRST_TIE = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID SECOND_TIE = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID OLDER_EVENT = UUID.fromString("00000000-0000-0000-0000-000000000012");
    private static final UUID OUTSIDE_DEFAULT = UUID.fromString("00000000-0000-0000-0000-000000000013");
    private static final UUID RETENTION_BOUNDARY = UUID.fromString("00000000-0000-0000-0000-000000000014");
    private static final UUID EXPIRED_EVENT = UUID.fromString("00000000-0000-0000-0000-000000000015");
    private static final UUID RUN_A = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID RUN_B = UUID.fromString("20000000-0000-0000-0000-000000000001");

    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("occ").withUsername("occ_admin").withPassword("synthetic-admin");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestClock clock;

    @BeforeEach
    void seed() {
        clock.set(NOW);
        jdbc.execute("TRUNCATE disruption.application_audit_event");
        insert(FIRST_TIE, NOW.minus(Duration.ofHours(1)), "OPERATIONS", "POLL", "SUCCESS", "actor-a", RUN_A, "bus-a",
                "{\"input\":\"Authorization: Bearer legacy-secret\",\"password\":\"legacy-password\",\"text\":\"safe\"}");
        insert(SECOND_TIE, NOW.minus(Duration.ofHours(1)), "OPERATIONS", "POLL", "FAILURE", "actor-b", RUN_B, "bus-b", "{}");
        insert(OLDER_EVENT, NOW.minus(Duration.ofHours(2)), "ACCOUNT_ASSIGNMENT", "ASSIGNMENT", "SUCCESS", "actor-a", RUN_A, "bus-c", "{}");
        insert(OUTSIDE_DEFAULT, NOW.minus(Duration.ofHours(25)), "OPERATIONS", "OLD_POLL", "SUCCESS", "actor-a", RUN_A, "bus-old", "{}");
        insert(RETENTION_BOUNDARY, NOW.minus(Duration.ofDays(90)), "OPERATIONS", "BOUNDARY", "SUCCESS", "actor-a", RUN_A, "bus-boundary", "{}");
        insert(EXPIRED_EVENT, NOW.minus(Duration.ofDays(91)), "OPERATIONS", "EXPIRED", "SUCCESS", "actor-a", RUN_A, "bus-expired", "{}");
    }

    @Test
    void defaultsToTwentyFourHoursAndOrdersTiesByDescendingIdAcrossRuns() throws Exception {
        mvc.perform(admin(get("/api/v1/disruption/admin/audit-events")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].id").value(SECOND_TIE.toString()))
                .andExpect(jsonPath("$.items[1].id").value(FIRST_TIE.toString()))
                .andExpect(jsonPath("$.items[2].id").value(OLDER_EVENT.toString()))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.retentionSeconds").value(Duration.ofDays(90).toSeconds()))
                .andExpect(jsonPath("$.collectionStartedAt").value(NOW.toString()))
                .andExpect(jsonPath("$.items[0].details").doesNotExist());
    }

    @Test
    void appliesAllFiltersBeforePagination() throws Exception {
        mvc.perform(admin(get("/api/v1/disruption/admin/audit-events")
                        .param("category", "OPERATIONS").param("eventType", "POLL").param("outcome", "FAILURE")
                        .param("actorSubject", "actor-b").param("runId", RUN_B.toString()).param("entityId", "bus-b")
                        .param("from", NOW.minus(Duration.ofHours(2)).toString()).param("to", NOW.toString())
                        .param("page", "0").param("size", "1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(SECOND_TIE.toString()))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void paginatesInStableOrderAndReturnsSanitizedDetailsOnlyFromDetailRoute() throws Exception {
        mvc.perform(admin(get("/api/v1/disruption/admin/audit-events").param("size", "1").param("page", "1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(FIRST_TIE.toString()))
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.totalPages").value(3));

        mvc.perform(admin(get("/api/v1/disruption/admin/audit-events/" + FIRST_TIE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(FIRST_TIE.toString()))
                .andExpect(jsonPath("$.summary").value("Safe summary password=[REDACTED]"))
                .andExpect(jsonPath("$.details.password").doesNotExist())
                .andExpect(jsonPath("$.details.input").value("Authorization: Bearer [REDACTED]"))
                .andExpect(jsonPath("$.details.text").value("safe"));
    }

    @Test
    void clampsSearchToTheRetainedWindowAndKeepsTheExactCutoffBoundary() throws Exception {
        mvc.perform(admin(get("/api/v1/disruption/admin/audit-events")
                        .param("from", NOW.minus(Duration.ofDays(91)).toString()).param("to", NOW.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.items[4].id").value(RETENTION_BOUNDARY.toString()))
                .andExpect(jsonPath("$.items[*].id").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(EXPIRED_EVENT.toString()))));
    }

    @Test
    void auditReadsDoNotMutateOperationalOrAuditRowsAndMissingDetailsReturnNotFound() throws Exception {
        String initialConsumerState = jdbc.queryForObject("SELECT mode || ':' || last_sequence FROM disruption.consumer_state WHERE id=1", String.class);
        int initialAuditCount = jdbc.queryForObject("SELECT count(*) FROM disruption.application_audit_event", Integer.class);

        mvc.perform(admin(get("/api/v1/disruption/admin/audit-events"))).andExpect(status().isOk());
        mvc.perform(admin(get("/api/v1/disruption/admin/audit-events/30000000-0000-0000-0000-000000000001")))
                .andExpect(status().isNotFound());

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT mode || ':' || last_sequence FROM disruption.consumer_state WHERE id=1", String.class)).isEqualTo(initialConsumerState);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM disruption.application_audit_event", Integer.class)).isEqualTo(initialAuditCount);
    }

    @Test
    void rejectsOutOfRangePagesAndInvertedTimeWindows() throws Exception {
        mvc.perform(admin(get("/api/v1/disruption/admin/audit-events").param("page", "-1")))
                .andExpect(status().isBadRequest());
        mvc.perform(admin(get("/api/v1/disruption/admin/audit-events").param("size", "101")))
                .andExpect(status().isBadRequest());
        mvc.perform(admin(get("/api/v1/disruption/admin/audit-events")
                        .param("from", NOW.toString()).param("to", NOW.minusSeconds(1).toString())))
                .andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder admin(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
        return request.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private void insert(UUID id, Instant occurredAt, String category, String type, String outcome, String actor,
            UUID runId, String entityId, String details) {
        jdbc.update("""
                INSERT INTO disruption.application_audit_event
                    (id, occurred_at, recorded_at, category, event_type, outcome, actor_subject, actor_type,
                     entity_type, entity_id, run_id, summary, details, source_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'CONTROLLER', 'TEST', ?, ?, ?, CAST(? AS jsonb), ?)
                """, id, Timestamp.from(occurredAt), Timestamp.from(NOW), category, type, outcome, actor, entityId,
                runId, id.equals(FIRST_TIE) ? "Safe summary password=legacy-password" : "Safe summary", details, "fixture:" + id);
    }

    static class TestClock extends Clock {
        private Instant now = NOW;
        void set(Instant instant) { now = instant; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }

    @TestConfiguration static class Time {
        @Bean @Primary TestClock testClock() { return new TestClock(); }
    }
}
