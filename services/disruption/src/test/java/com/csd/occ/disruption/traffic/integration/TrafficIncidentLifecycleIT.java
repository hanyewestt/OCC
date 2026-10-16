package com.csd.occ.disruption.traffic.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.csd.occ.disruption.traffic.domain.TrafficIncidentPollPort;
import com.csd.occ.disruption.traffic.domain.TrafficIncidentEvidencePort;
import com.csd.occ.disruption.traffic.domain.TrafficIncidentReadService;
import com.csd.occ.disruption.traffic.domain.TrafficIncidentState;
import com.csd.occ.disruption.traffic.domain.TrafficIncidentSyncService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.profiles.active=test", "OCC_DISRUPTION_INTERNAL_PORT=8002",
        "OCC_DISRUPTION_CONSUMER_ENABLED=false", "OCC_DISRUPTION_PLANNING_WORKER_ENABLED=false"
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class TrafficIncidentLifecycleIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("occ").withUsername("occ_admin").withPassword("synthetic-admin");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "occ_admin");
        registry.add("spring.datasource.password", () -> "synthetic-admin");
    }

    @Autowired private TrafficIncidentSyncService synchronization;
    @Autowired private TrafficIncidentReadService incidentReads;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private TrafficIncidentPollPort feed;
    @MockitoBean private TrafficIncidentEvidencePort evidenceCodec;

    @BeforeEach
    void resetRows() {
        reset(feed, evidenceCodec);
        org.mockito.Mockito.when(evidenceCodec.encode(any(), any(), any())).thenReturn("{}");
        jdbc.execute("TRUNCATE disruption.planning_outbox, disruption.event_evidence, disruption.disruption_event, disruption.traffic_incident_state, disruption.disruption_processing_state, disruption.disruption");
        jdbc.update("UPDATE disruption.traffic_poll_checkpoint SET completed_at = NULL, poll_run_id = NULL WHERE id = 1");
    }

    @Test
    void opensUpdatesAndClearsOnlyAfterTwoCompleteAbsentPolls() {
        UUID demoRun = UUID.randomUUID();
        String key = "a".repeat(64);
        Instant start = Instant.now().minusSeconds(3);
        var opened = poll(UUID.randomUUID(), start, "SUCCESS", true, "SIMULATION", demoRun,
                List.of(fact(UUID.randomUUID(), key, "Vehicle Breakdown", "On Teck Whye Ave", start, demoRun)));
        synchronization.process(opened, java.time.Duration.ofMinutes(5), 2);
        synchronization.process(opened, java.time.Duration.ofMinutes(5), 2);

        assertThat(count("disruption.traffic_incident_state")).isEqualTo(1);
        assertThat(count("disruption.disruption")).isEqualTo(1);
        assertThat(eventActions()).containsExactly("OPENED");
        assertThat(incidentReads.currentOpen(0, 20).getContent())
                .singleElement().extracting(TrafficIncidentState::disruptionId).isNotNull();

        Instant updateAt = start.plusSeconds(1);
        synchronization.process(poll(UUID.randomUUID(), updateAt, "SUCCESS", true, "SIMULATION", demoRun,
                List.of(fact(UUID.randomUUID(), key, "Vehicle Breakdown", "Lane partially blocked", updateAt, demoRun))),
                java.time.Duration.ofMinutes(5), 2);
        assertThat(eventActions()).containsExactly("OPENED", "UPDATED");

        Instant incompleteAt = updateAt.plusSeconds(1);
        synchronization.process(poll(UUID.randomUUID(), incompleteAt, "PARTIAL", false, "SIMULATION", demoRun,
                List.of()), java.time.Duration.ofMinutes(5), 2);
        assertThat(jdbc.queryForObject("SELECT absence_count FROM disruption.traffic_incident_state", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM disruption.disruption", String.class)).isEqualTo("ACTIVE");

        Instant absentAt = incompleteAt.plusSeconds(1);
        synchronization.process(poll(UUID.randomUUID(), absentAt, "SUCCESS", true, "SIMULATION", demoRun, List.of()),
                java.time.Duration.ofMinutes(5), 2);
        assertThat(jdbc.queryForObject("SELECT absence_count FROM disruption.traffic_incident_state", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM disruption.disruption", String.class)).isEqualTo("ACTIVE");

        Instant clearedAt = absentAt.plusSeconds(1);
        synchronization.process(poll(UUID.randomUUID(), clearedAt, "SUCCESS", true, "SIMULATION", demoRun, List.of()),
                java.time.Duration.ofMinutes(5), 2);
        assertThat(jdbc.queryForObject("SELECT status FROM disruption.disruption", String.class)).isEqualTo("CLEARED");
        assertThat(eventActions()).containsExactly("OPENED", "UPDATED", "CLEARED");
        assertThat(count("disruption.event_evidence")).isEqualTo(3);
        assertThat(count("disruption.planning_outbox")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM disruption.planning_outbox "
                + "WHERE payload_json LIKE '%TRAFFIC_INCIDENT%' AND status = 'PENDING'", Integer.class)).isEqualTo(3);
    }

    @Test
    void failedAndStalePollsAdvanceTheFeedCursorWithoutMutatingIncidentState() {
        UUID demoRun = UUID.randomUUID();
        String key = "b".repeat(64);
        Instant staleAt = Instant.now().minusSeconds(600);
        synchronization.process(poll(UUID.randomUUID(), staleAt, "SUCCESS", true, "SIMULATION", demoRun,
                List.of(fact(UUID.randomUUID(), key, "Road Obstruction", "Westbound", staleAt, demoRun))),
                java.time.Duration.ofMinutes(5), 2);
        assertThat(count("disruption.traffic_incident_state")).isZero();

        Instant now = Instant.now().minusSeconds(2);
        synchronization.process(poll(UUID.randomUUID(), now, "SUCCESS", true, "SIMULATION", demoRun,
                List.of(fact(UUID.randomUUID(), key, "Road Obstruction", "Westbound", now, demoRun))),
                java.time.Duration.ofMinutes(5), 2);

        synchronization.process(poll(UUID.randomUUID(), now.plusSeconds(1), "SUCCESS", true, "SIMULATION", demoRun,
                List.of(fact(UUID.randomUUID(), "invalid", "Road Obstruction", "Westbound", now.plusSeconds(1), demoRun))),
                java.time.Duration.ofMinutes(5), 2);
        assertThat(jdbc.queryForObject("SELECT absence_count FROM disruption.traffic_incident_state", Integer.class)).isZero();

        synchronization.process(poll(UUID.randomUUID(), now.plusSeconds(2), "FAILURE", false, "SIMULATION", demoRun, List.of()),
                java.time.Duration.ofMinutes(5), 2);
        assertThat(jdbc.queryForObject("SELECT absence_count FROM disruption.traffic_incident_state", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM disruption.disruption", String.class)).isEqualTo("ACTIVE");
        assertThat(eventActions()).containsExactly("OPENED");
    }

    @Test
    void liveAndDemoIncidentsWithTheSameProviderKeyRemainIsolated() {
        UUID demoRun = UUID.randomUUID();
        String key = "c".repeat(64);
        Instant start = Instant.now().minusSeconds(5);
        synchronization.process(poll(UUID.randomUUID(), start, "SUCCESS", true, "OBSERVATION_ONLY", null,
                List.of(fact(UUID.randomUUID(), key, "Road obstruction", "Live lane", start, null))),
                java.time.Duration.ofMinutes(5), 2);
        synchronization.process(poll(UUID.randomUUID(), start.plusSeconds(1), "SUCCESS", true, "SIMULATION", demoRun,
                List.of(fact(UUID.randomUUID(), key, "Road obstruction", "Demo lane", start.plusSeconds(1), demoRun))),
                java.time.Duration.ofMinutes(5), 2);

        assertThat(count("disruption.traffic_incident_state")).isEqualTo(2);
        assertThat(eventActions()).containsExactly("OPENED", "OPENED");
        assertThat(jdbc.queryForObject("SELECT absence_count FROM disruption.traffic_incident_state WHERE classification='OBSERVATION_ONLY'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT absence_count FROM disruption.traffic_incident_state WHERE classification='SIMULATION'", Integer.class)).isZero();

        synchronization.process(poll(UUID.randomUUID(), start.plusSeconds(2), "SUCCESS", true, "OBSERVATION_ONLY", null,
                List.of()), java.time.Duration.ofMinutes(5), 2);

        assertThat(jdbc.queryForObject("SELECT absence_count FROM disruption.traffic_incident_state WHERE classification='OBSERVATION_ONLY'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT absence_count FROM disruption.traffic_incident_state WHERE classification='SIMULATION'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM disruption.disruption WHERE classification='SIMULATION'", String.class)).isEqualTo("ACTIVE");
    }

    @Test
    void failedTrafficUpdateRollsBackTheIncidentStateAndEventTogether() {
        UUID demoRun = UUID.randomUUID();
        String key = "d".repeat(64);
        Instant start = Instant.now().minusSeconds(5);
        synchronization.process(poll(UUID.randomUUID(), start, "SUCCESS", true, "SIMULATION", demoRun,
                List.of(fact(UUID.randomUUID(), key, "Road obstruction", "Original report", start, demoRun))),
                java.time.Duration.ofMinutes(5), 2);
        doThrow(new IllegalStateException("synthetic evidence encoding failure"))
                .when(evidenceCodec).encode(any(), any(), eq("UPDATED"));

        assertThatThrownBy(() -> synchronization.process(poll(UUID.randomUUID(), start.plusSeconds(1), "SUCCESS", true,
                "SIMULATION", demoRun,
                List.of(fact(UUID.randomUUID(), key, "Road obstruction", "Changed report", start.plusSeconds(1), demoRun))),
                java.time.Duration.ofMinutes(5), 2)).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("SELECT message FROM disruption.traffic_incident_state", String.class))
                .isEqualTo("Original report");
        Instant savedCursor = jdbc.queryForObject(
                "SELECT completed_at FROM disruption.traffic_poll_checkpoint WHERE id=1",
                (result, row) -> result.getTimestamp(1).toInstant());
        assertThat(Duration.between(start, savedCursor).abs()).isLessThanOrEqualTo(Duration.ofNanos(500));
        assertThat(eventActions()).containsExactly("OPENED");
        assertThat(count("disruption.event_evidence")).isEqualTo(1);
        assertThat(count("disruption.planning_outbox")).isEqualTo(1);
    }

    private TrafficIncidentPollPort.TrafficIncidentPoll poll(UUID id, Instant completedAt, String outcome,
            boolean complete, String classification, UUID demoRunId,
            List<TrafficIncidentPollPort.TrafficIncidentFact> incidents) {
        return new TrafficIncidentPollPort.TrafficIncidentPoll(id, "DEMO", outcome, complete, classification,
                demoRunId, completedAt.minusMillis(100), completedAt, incidents);
    }

    private TrafficIncidentPollPort.TrafficIncidentFact fact(UUID id, String key, String type, String message,
            Instant at, UUID demoRunId) {
        String classification = demoRunId == null ? "OBSERVATION_ONLY" : "SIMULATION";
        return new TrafficIncidentPollPort.TrafficIncidentFact(id, UUID.randomUUID(), key, type, message,
                new BigDecimal("1.3802550"), new BigDecimal("103.7550750"), at.minusMillis(200), at,
                classification, demoRunId);
    }

    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }
    private List<String> eventActions() {
        return jdbc.queryForList("SELECT action FROM disruption.disruption_event ORDER BY triggered_at, id", String.class);
    }
}
