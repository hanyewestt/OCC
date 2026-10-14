package com.csd.occ.disruption.bunching.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.csd.occ.disruption.bunching.domain.DiagnosisOutbox;
import com.csd.occ.disruption.bunching.domain.DiagnosisOutboxRepository;
import com.csd.occ.disruption.bunching.domain.DiagnosisOutboxService;
import com.csd.occ.disruption.bunching.infrastructure.config.DiagnosisOutboxProperties;
import com.csd.occ.disruption.bunching.infrastructure.scheduling.BunchingDiagnosisDispatcher;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@ActiveProfiles("test")
@SpringBootTest(properties = {
        "occ.disruption.consumer-enabled=false",
        "occ.disruption.planning.worker-enabled=false",
        "spring.datasource.url=jdbc:postgresql://unused/test",
        "spring.datasource.password=synthetic-test-only"
})
@Testcontainers(disabledWithoutDocker = true)
@Import(BunchingDiagnosisDispatcherIT.FixedClockConfiguration.class)
class BunchingDiagnosisDispatcherIT {
    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withInitScript("db/disruption-schema.sql");

    @Autowired DiagnosisOutboxRepository repository;
    @Autowired DiagnosisOutboxService outbox;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clearOutbox() {
        jdbc.execute("TRUNCATE disruption.planning_outbox, disruption.event_evidence, disruption.disruption_event, disruption.traffic_incident_state, disruption.disruption_processing_state, disruption.disruption");
    }

    @Test
    void acceptedDeliveryIsPersistedAndNeverSentTwice() {
        Fixture fixture = fixture(0);
        DiagnosisOutbox claim = insertDueOutbox();
        expectRequest(fixture.server, claim.id(), claim.eventId(), HttpStatus.ACCEPTED);

        fixture.dispatcher.dispatchPending();
        fixture.dispatcher.dispatchPending();

        assertThat(status(claim.id())).isEqualTo("DELIVERED");
        assertThat(attemptCount(claim.id())).isEqualTo(1);
        fixture.server.verify();
    }

    @Test
    void transientFailurePersistsCappedJitteredRetry() {
        Fixture fixture = fixture(0);
        DiagnosisOutbox claim = insertDueOutbox();
        fixture.server.expect(requestTo("http://diagnosis.test/internal/v1/planning/requests"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        fixture.dispatcher.dispatchPending();

        assertThat(status(claim.id())).isEqualTo("PENDING");
        assertThat(lastStatus(claim.id())).isEqualTo(503);
        assertThat(failureCode(claim.id())).isEqualTo("PLANNING_REQUEST_TRANSIENT_HTTP_FAILURE");
        assertThat(Duration.between(NOW, nextAttempt(claim.id())))
                .isBetween(Duration.ofMillis(1_600), Duration.ofMillis(2_400));
        fixture.server.verify();
    }

    @Test
    void exhaustedAttemptsBecomeTerminal() {
        Fixture fixture = fixture(1);
        DiagnosisOutbox claim = insertDueOutbox();
        fixture.server.expect(requestTo("http://diagnosis.test/internal/v1/planning/requests"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        fixture.dispatcher.dispatchPending();

        assertThat(status(claim.id())).isEqualTo("FAILED_TERMINAL");
        assertThat(failureCode(claim.id())).isEqualTo("PLANNING_REQUEST_TRANSIENT_HTTP_FAILURE");
        fixture.server.verify();
    }

    @Test
    void expiredClaimLeaseIsRecoveredByTheDispatcher() {
        DiagnosisOutbox claim = insertDueOutbox();
        assertThat(outbox.claimDue(1, Duration.ofMinutes(1))).hasSize(1);
        jdbc.update("UPDATE disruption.planning_outbox SET lease_until = ? WHERE id = ?",
                Timestamp.from(NOW.minusSeconds(1)), claim.id());
        Fixture fixture = fixture(0);
        expectRequest(fixture.server, claim.id(), claim.eventId(), HttpStatus.ACCEPTED);

        fixture.dispatcher.dispatchPending();

        assertThat(status(claim.id())).isEqualTo("DELIVERED");
        assertThat(attemptCount(claim.id())).isEqualTo(2);
        fixture.server.verify();
    }

    private Fixture fixture(int maxAttempts) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://diagnosis.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DiagnosisOutboxProperties properties = new DiagnosisOutboxProperties(URI.create("http://diagnosis.test"),
                true, Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofMinutes(1), 10, maxAttempts,
                Duration.ofSeconds(2), Duration.ofSeconds(60));
        return new Fixture(new BunchingDiagnosisDispatcher(outbox, properties, builder.build(),
                Clock.fixed(NOW, ZoneOffset.UTC)), server);
    }

    private DiagnosisOutbox insertDueOutbox() {
        UUID eventId = UUID.randomUUID();
        UUID disruptionId = UUID.randomUUID();
        UUID demoRunId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO disruption.disruption
                    (id, kind, classification, demo_run_id, status, opened_at, updated_at)
                VALUES (?, 'BUNCHING', 'SIMULATION', ?, 'ACTIVE', ?, ?)
                """, disruptionId, demoRunId, Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO disruption.disruption_event
                    (id, disruption_id, kind, action, classification, service_id, stop_code, direction_id,
                     raw_observation_id, poll_run_id, fetched_at, triggered_at, evidence,
                     demo_run_id, expected_headway_seconds,
                     first_observed_headway_seconds, second_observed_headway_seconds)
                VALUES (?, ?, 'BUNCHING', 'OPENED', 'SIMULATION', '190', '44009', 1,
                        ?, ?, ?, ?, '{}'::jsonb, ?, 600, 360, 390)
                """, eventId, disruptionId, UUID.randomUUID(), UUID.randomUUID(),
                Timestamp.from(NOW), Timestamp.from(NOW), demoRunId);
        DiagnosisOutbox row = new DiagnosisOutbox(UUID.randomUUID(), eventId, NOW);
        return repository.saveAndFlush(row);
    }

    private void expectRequest(MockRestServiceServer server, UUID id, UUID eventId, HttpStatus status) {
        server.expect(requestTo("http://diagnosis.test/internal/v1/planning/requests"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", id.toString()))
                .andExpect(content().json("""
                        {"eventType":"BUS_BUNCHING","producer":"disruption","eventId":"%s"}
                        """.formatted(eventId)))
                .andRespond(withStatus(status));
    }

    private String status(UUID id) {
        return jdbc.queryForObject("SELECT status FROM disruption.planning_outbox WHERE id = ?", String.class, id);
    }

    private int attemptCount(UUID id) {
        return jdbc.queryForObject("SELECT attempt_count FROM disruption.planning_outbox WHERE id = ?", Integer.class, id);
    }

    private Integer lastStatus(UUID id) {
        return jdbc.queryForObject("SELECT last_http_status FROM disruption.planning_outbox WHERE id = ?", Integer.class, id);
    }

    private String failureCode(UUID id) {
        return jdbc.queryForObject("SELECT failure_code FROM disruption.planning_outbox WHERE id = ?", String.class, id);
    }

    private Instant nextAttempt(UUID id) {
        return jdbc.queryForObject("SELECT next_attempt_at FROM disruption.planning_outbox WHERE id = ?",
                (result, row) -> result.getTimestamp(1).toInstant(), id);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    private record Fixture(BunchingDiagnosisDispatcher dispatcher, MockRestServiceServer server) { }
}
