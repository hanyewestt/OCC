package com.csd.occ.disruption.hazard.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.csd.occ.disruption.bunching.domain.ConsumerSelectionService;
import com.csd.occ.disruption.bunching.domain.PlanningEventSubmissionPort;
import com.csd.occ.disruption.bunching.infrastructure.scheduling.BunchingRetentionService;
import com.csd.occ.disruption.hazard.domain.HazardPollPort;
import com.csd.occ.disruption.hazard.domain.HazardReadService;
import com.csd.occ.disruption.hazard.domain.HazardCheckpoint;
import com.csd.occ.disruption.hazard.domain.HazardReassessmentService;
import com.csd.occ.disruption.hazard.domain.HazardStateRepository;
import com.csd.occ.disruption.hazard.domain.HazardSyncService;
import com.csd.occ.disruption.hazard.domain.HazardTransitPort;
import com.csd.occ.disruption.traffic.domain.TrafficIncidentPollPort;
import com.csd.occ.disruption.traffic.domain.TrafficIncidentSyncService;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "OCC_DISRUPTION_INTERNAL_PORT=8002", "OCC_DISRUPTION_CONSUMER_ENABLED=false",
        "OCC_DISRUPTION_PLANNING_WORKER_ENABLED=false", "OCC_DISRUPTION_HAZARD_ENABLED=false",
        "OCC_DISRUPTION_HAZARD_STATE_RETENTION=30d"
})
@ActiveProfiles("test")
@Import(HazardLifecycleIT.Time.class)
@Testcontainers(disabledWithoutDocker = true)
class HazardLifecycleIT {
    private static final Instant NOW = Instant.parse("2026-10-25T02:00:00Z");
    private static final Duration FRESHNESS = Duration.ofMinutes(5);
    private static final Duration ABSENCE_PERIOD = Duration.ofSeconds(20);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("occ").withUsername("occ_admin").withPassword("synthetic-admin");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "occ_admin");
        registry.add("spring.datasource.password", () -> "synthetic-admin");
    }

    @Autowired HazardSyncService synchronization;
    @Autowired PlanningEventSubmissionPort codec;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestClock clock;
    @Autowired HazardStateRepository hazards;
    @Autowired BunchingRetentionService retention;
    @Autowired HazardReadService hazardReads;
    @Autowired HazardReassessmentService reassessment;
    @Autowired ConsumerSelectionService selections;
    @Autowired TrafficIncidentSyncService trafficSynchronization;
    @MockitoBean HazardTransitPort transit;

    @Test
    void roadworksFilterIncludesTrafficRoadworksExclusivelyBeforePagination() {
        process("ROADWORKS", poll("ROADWORKS", NOW, "SUCCESS", true, null,
                List.of(fact("ROADWORKS", "approved-work", "OBSERVED", null, null, List.of()))));
        process("FLOOD", poll("FLOOD", NOW, "SUCCESS", true, null,
                List.of(fact("FLOOD", "flood", "OBSERVED", null, null, List.of()))));
        var types = List.of("Roadwork", "Road Works", "ROADWORKS", "Accident", "Vehicle Breakdown", "Roadwork");
        var incidents = java.util.stream.IntStream.range(0, types.size()).mapToObj(index ->
                new TrafficIncidentPollPort.TrafficIncidentFact(UUID.randomUUID(), UUID.randomUUID(),
                        String.format("%064d", index), types.get(index), "Reported condition",
                        new java.math.BigDecimal("1.3"), new java.math.BigDecimal("103.8"),
                        NOW, NOW, "OBSERVATION_ONLY", null)).toList();
        trafficSynchronization.process(new TrafficIncidentPollPort.TrafficIncidentPoll(UUID.randomUUID(),
                "TRAFFIC_INCIDENTS", "SUCCESS", true, "OBSERVATION_ONLY", null, NOW, NOW, incidents), FRESHNESS, 2);
        jdbc.update("UPDATE disruption.traffic_incident_state SET status='CLEARED', cleared_at=updated_at WHERE source_incident_key=?",
                String.format("%064d", 5));
        var otherRun = UUID.randomUUID();
        var simulated = new TrafficIncidentPollPort.TrafficIncidentFact(UUID.randomUUID(), UUID.randomUUID(),
                "a".repeat(64), "Roadwork", "Other simulation", new java.math.BigDecimal("1.3"),
                new java.math.BigDecimal("103.8"), NOW, NOW, "SIMULATION", otherRun);
        trafficSynchronization.process(new TrafficIncidentPollPort.TrafficIncidentPoll(UUID.randomUUID(),
                "TRAFFIC_INCIDENTS", "SUCCESS", true, "SIMULATION", otherRun, NOW, NOW.plusSeconds(1), List.of(simulated)), FRESHNESS, 2);

        var first = hazardReads.list("ROADWORKS", 0, 2);
        var second = hazardReads.list("ROADWORKS", 1, 2);
        assertThat(first.totalElements()).isEqualTo(4);
        assertThat(second.totalElements()).isEqualTo(4);
        assertThat(first.items()).hasSize(2);
        assertThat(second.items()).hasSize(2);
        assertThat(java.util.stream.Stream.concat(first.items().stream(), second.items().stream())
                .map(item -> item.facts().title()).toList())
                .containsExactlyInAnyOrder("Synthetic hazard", "Roadwork", "Road Works", "ROADWORKS");
        var traffic = hazardReads.list("TRAFFIC_INCIDENT", 0, 20);
        assertThat(traffic.totalElements()).isEqualTo(2);
        assertThat(traffic.items()).extracting(item -> item.facts().title())
                .containsExactlyInAnyOrder("Accident", "Vehicle Breakdown");
        var all = hazardReads.list(null, 0, 20);
        assertThat(all.totalElements()).isEqualTo(7);
        assertThat(all.items()).extracting(HazardReadService.View::disruptionId).doesNotHaveDuplicates();
        assertThat(hazardReads.list("FLOOD", 0, 20).totalElements()).isEqualTo(1);
        selections.select("DEMO", otherRun);
        assertThat(hazardReads.list("ROADWORKS", 0, 20).totalElements()).isEqualTo(5);
    }

    @BeforeEach
    void resetRows() {
        clock.set(NOW);
        jdbc.update("UPDATE disruption.consumer_state SET mode='LIVE', demo_run_id=NULL WHERE id=1");
        jdbc.execute("TRUNCATE disruption.planning_outbox, disruption.event_evidence, disruption.disruption_event, "
                + "disruption.hazard_state, disruption.hazard_feed_health, disruption.traffic_incident_state, "
                + "disruption.disruption_processing_state, disruption.disruption");
        jdbc.update("UPDATE disruption.hazard_checkpoint SET completed_at=NULL, poll_run_id=NULL");
        jdbc.update("UPDATE disruption.traffic_poll_checkpoint SET completed_at=NULL, poll_run_id=NULL WHERE id=1");
    }

    @Test
    void replaysKeepOneIncidentEventAndRealPlanningPayload() {
        var opened = poll("FLOOD", NOW, "SUCCESS", true, null,
                List.of(fact("FLOOD", "flood-1", "OBSERVED", null, null, List.of())));
        process("FLOOD", opened);
        process("FLOOD", opened);
        clock.set(NOW.plusSeconds(1));
        process("FLOOD", poll("FLOOD", clock.instant(), "SUCCESS", true, null,
                List.of(fact("FLOOD", "flood-1", "OBSERVED", null, null, List.of()))));

        assertThat(count("hazard_state")).isEqualTo(1);
        assertThat(count("disruption")).isEqualTo(1);
        assertThat(count("disruption_event")).isEqualTo(1);
        assertThat(count("planning_outbox")).isEqualTo(1);
        String payload = jdbc.queryForObject("SELECT payload_json FROM disruption.planning_outbox", String.class);
        UUID eventId = jdbc.queryForObject("SELECT event_id FROM disruption.planning_outbox", UUID.class);
        assertThat(payload).contains("\"eventType\":\"HAZARD\"", "Avoid the affected area", "flood-1");
        assertThat(codec.fingerprint(payload, eventId)).isEqualTo(jdbc.queryForObject(
                "SELECT payload_fingerprint FROM disruption.planning_outbox", String.class));
        assertThat(synchronization.cursor("FLOOD").completedAt()).isEqualTo(clock.instant());
        var healthy = hazardReads.list(null, 0, 20).feeds().stream()
                .filter(feed -> feed.kind().equals("FLOOD") && feed.classification().equals("OBSERVATION_ONLY"))
                .findFirst().orElseThrow();
        assertThat(healthy.status()).isEqualTo("HEALTHY");
        clock.set(clock.instant().plus(FRESHNESS));
        assertThat(hazardReads.list(null, 0, 20).feeds()).filteredOn(feed -> feed.kind().equals("FLOOD")
                && feed.classification().equals("OBSERVATION_ONLY")).singleElement()
                .extracting(HazardCheckpoint.FeedHealth::status).isEqualTo("STALE");
        assertThat(hazards.findAll().getFirst().status()).isEqualTo("ACTIVE");
    }

    @Test
    void cancellationOnlyClearsReferencedAlertsAndCannotReopenFromOldSourceText() {
        var first = fact("FLOOD", "flood-1", "OBSERVED", null, null, List.of());
        var second = fact("FLOOD", "flood-2", "OBSERVED", null, null, List.of());
        process("FLOOD", poll("FLOOD", NOW, "SUCCESS", true, null, List.of(first, second)));
        clock.set(NOW.plusSeconds(1));
        process("FLOOD", poll("FLOOD", clock.instant(), "SUCCESS", true, null, List.of(
                fact("FLOOD", "cancel-1", "CANCEL", null, null, List.of("flood-1")),
                fact("FLOOD", "cancel-unknown", "CANCEL", null, null, List.of("unknown")))));
        assertThat(state("flood-1", "status")).isEqualTo("CLEARED");
        assertThat(state("flood-1", "inactive_reason")).isEqualTo("SOURCE_CANCELLED");
        assertThat(state("flood-2", "status")).isEqualTo("ACTIVE");
        assertThat(count("hazard_state")).isEqualTo(2);
        assertThat(count("disruption_event")).isEqualTo(3);
        String resolution = jdbc.queryForObject("SELECT evidence::text FROM disruption.disruption_event WHERE action='CLEARED'",
                String.class);
        assertThat(resolution).contains("\"resolutionEvidence\": {",
                "\"method\": \"SOURCE_OBSERVATION\"",
                "\"sourceEventKey\": \"cancel-1\"", "\"sourceAction\": \"CANCEL\"");
        clock.set(NOW.plusSeconds(2));
        process("FLOOD", poll("FLOOD", clock.instant(), "SUCCESS", true, null, List.of(first, second)));
        assertThat(state("flood-1", "status")).isEqualTo("CLEARED");
        assertThat(count("disruption_event")).isEqualTo(3);
    }

    @Test
    void delayedAuthoritativeResolutionsClearAndQueueOnceDespiteStalePolls() {
        for (String kind : List.of("FLOOD", "RAIL_DISRUPTION")) {
            String key = kind + "-active";
            process(kind, poll(kind, NOW, "SUCCESS", true, null,
                    List.of(fact(kind, key, "OBSERVED", null, NOW.plus(Duration.ofDays(1)), List.of()))));
            Instant resolutionAt = NOW.plusSeconds(1);
            var resolution = poll(kind, resolutionAt, "SUCCESS", true, null,
                    List.of(fact(kind, "FLOOD".equals(kind) ? "cancel" : key,
                            "FLOOD".equals(kind) ? "CANCEL" : "RECOVERED", null, null, List.of(key)),
                            fact(kind, "stale-new-" + kind, "OBSERVED", null, null, List.of())));
            clock.set(NOW.plus(FRESHNESS).plusSeconds(60));
            process(kind, resolution);
            process(kind, resolution);
            process(kind, poll(kind, clock.instant(), "SUCCESS", true, null, List.of()));
            assertThat(state(key, "status")).isEqualTo("CLEARED");
            assertThat(state(key, "inactive_reason")).isEqualTo(
                    "FLOOD".equals(kind) ? "SOURCE_CANCELLED" : "SOURCE_RECOVERED");
            clock.set(NOW);
        }
        assertThat(count("hazard_state")).isEqualTo(2);
        assertThat(count("planning_outbox")).isEqualTo(4);
        assertThat(jdbc.queryForList("SELECT payload_json FROM disruption.planning_outbox WHERE event_id IN "
                + "(SELECT id FROM disruption.disruption_event WHERE action='CLEARED')", String.class))
                .hasSize(2).allSatisfy(payload -> assertThat(payload).contains(
                        "\"action\":\"CLEARED\"", "\"method\":\"SOURCE_OBSERVATION\""));
    }

    @Test
    void demoSuccessDoesNotMaskLiveFailure() {
        process("FLOOD", poll("FLOOD", NOW, "FAILED", false, null, List.of()));
        clock.set(NOW.plusSeconds(1));
        process("FLOOD", poll("FLOOD", clock.instant(), "SUCCESS", true, UUID.randomUUID(), List.of()));
        var feeds = hazardReads.list(null, 0, 20).feeds().stream().filter(feed -> feed.kind().equals("FLOOD")).toList();
        assertThat(feeds).hasSize(2);
        assertThat(feeds).filteredOn(feed -> feed.classification().equals("OBSERVATION_ONLY")).singleElement()
                .satisfies(feed -> { assertThat(feed.status()).isEqualTo("FAILED"); assertThat(feed.lastSuccessAt()).isNull(); });
        assertThat(feeds).filteredOn(feed -> feed.classification().equals("SIMULATION")).singleElement()
                .satisfies(feed -> { assertThat(feed.status()).isEqualTo("HEALTHY"); assertThat(feed.lastSuccessAt()).isEqualTo(clock.instant()); });
    }

    @Test
    void expiryAppliesExactlyAtDeadlineAndRepeatedSourceDoesNotReopenIt() {
        var flood = fact("FLOOD", "expiring", "OBSERVED", null, NOW.plusSeconds(30), List.of());
        process("FLOOD", poll("FLOOD", NOW, "SUCCESS", true, null, List.of(flood)));
        clock.set(NOW.plusSeconds(29));
        process("FLOOD", poll("FLOOD", clock.instant(), "SUCCESS", true, null, List.of(flood)));
        assertThat(state("expiring", "status")).isEqualTo("ACTIVE");
        assertThat(hazards.findVisible("FLOOD", null, clock.instant(), PageRequest.of(0, 20)).getTotalElements()).isEqualTo(1);
        clock.set(NOW.plusSeconds(30));
        assertThat(hazards.findVisible("FLOOD", null, clock.instant(), PageRequest.of(0, 20)).getTotalElements()).isZero();
        assertThat(state("expiring", "status")).isEqualTo("ACTIVE");
        process("FLOOD", poll("FLOOD", clock.instant(), "SUCCESS", true, null, List.of(flood)));
        assertThat(state("expiring", "status")).isEqualTo("CLEARED");
        assertThat(state("expiring", "inactive_reason")).isEqualTo("SOURCE_EXPIRED");
        clock.set(NOW.plusSeconds(31));
        process("FLOOD", poll("FLOOD", clock.instant(), "SUCCESS", true, null, List.of(flood)));
        assertThat(count("disruption_event")).isEqualTo(2);
    }

    @Test
    void scheduledRoadworksActivateThenRetireOnlyAfterCountAndElapsedPeriod() {
        var works = fact("ROADWORKS", "works-1", "OBSERVED", NOW.plusSeconds(10), NOW.plusSeconds(300), List.of());
        process("ROADWORKS", poll("ROADWORKS", NOW, "SUCCESS", true, null, List.of(works)));
        assertThat(state("works-1", "status")).isEqualTo("SCHEDULED");
        clock.set(NOW.plusSeconds(10));
        process("ROADWORKS", poll("ROADWORKS", clock.instant(), "SUCCESS", true, null, List.of(works)));
        assertThat(state("works-1", "status")).isEqualTo("ACTIVE");
        clock.set(NOW.plusSeconds(20));
        process("ROADWORKS", poll("ROADWORKS", clock.instant(), "SUCCESS", true, null, List.of()));
        assertThat(absences("works-1")).isEqualTo(1);
        clock.set(NOW.plusSeconds(25));
        process("ROADWORKS", poll("ROADWORKS", clock.instant(), "PARTIAL", false, null, List.of()));
        clock.set(NOW.plusSeconds(26));
        process("ROADWORKS", poll("ROADWORKS", clock.instant(), "FAILED", false, null, List.of()));
        assertThat(absences("works-1")).isEqualTo(1);
        assertThat(synchronization.cursor("ROADWORKS").completedAt()).isEqualTo(clock.instant());
        clock.set(NOW.plusSeconds(30));
        process("ROADWORKS", poll("ROADWORKS", clock.instant(), "SUCCESS", true, null, List.of()));
        assertThat(absences("works-1")).isEqualTo(2);
        assertThat(state("works-1", "status")).isEqualTo("ACTIVE");
        clock.set(NOW.plusSeconds(40));
        process("ROADWORKS", poll("ROADWORKS", clock.instant(), "SUCCESS", true, null, List.of()));
        assertThat(state("works-1", "status")).isEqualTo("CLEARED");
        assertThat(state("works-1", "inactive_reason")).isEqualTo("NO_LONGER_REPORTED");
        assertThat(count("disruption_event")).isEqualTo(3);
        assertThat(count("planning_outbox")).isEqualTo(3);
    }

    @Test
    void failedOutboxInsertRollsBackIncidentEventAndCheckpointThenRetryCommitsOnce() {
        var input = poll("FLOOD", NOW, "SUCCESS", true, null,
                List.of(fact("FLOOD", "rollback", "OBSERVED", null, null, List.of())));
        jdbc.execute("ALTER TABLE disruption.planning_outbox ADD CONSTRAINT test_reject_hazard_outbox CHECK(false) NOT VALID");
        try {
            assertThatThrownBy(() -> process("FLOOD", input)).isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("ALTER TABLE disruption.planning_outbox DROP CONSTRAINT test_reject_hazard_outbox");
        }
        assertThat(count("hazard_state")).isZero();
        assertThat(count("disruption")).isZero();
        assertThat(count("disruption_event")).isZero();
        assertThat(count("planning_outbox")).isZero();
        assertThat(synchronization.cursor("FLOOD").completedAt()).isNull();
        process("FLOOD", input);
        process("FLOOD", input);
        assertThat(count("hazard_state")).isEqualTo(1);
        assertThat(count("disruption_event")).isEqualTo(1);
        assertThat(count("planning_outbox")).isEqualTo(1);
        assertThat(synchronization.cursor("FLOOD").pollRunId()).isEqualTo(input.pollRunId());
    }

    @Test
    void failedUpdateEventLeavesPreviousFactsAndCursorIntact() {
        var initial = poll("FLOOD", NOW, "SUCCESS", true, null,
                List.of(fact("FLOOD", "update", "OBSERVED", null, null, List.of())));
        process("FLOOD", initial);
        clock.set(NOW.plusSeconds(1));
        var update = poll("FLOOD", clock.instant(), "SUCCESS", true, null,
                List.of(fact("FLOOD", "update", "OBSERVED", null, NOW.plusSeconds(300), List.of())));
        jdbc.execute("ALTER TABLE disruption.disruption_event ADD CONSTRAINT test_reject_hazard_event CHECK(false) NOT VALID");
        try {
            assertThatThrownBy(() -> process("FLOOD", update)).isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("ALTER TABLE disruption.disruption_event DROP CONSTRAINT test_reject_hazard_event");
        }
        assertThat(count("disruption_event")).isEqualTo(1);
        assertThat(count("planning_outbox")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT facts->>'effectiveUntil' FROM disruption.hazard_state", String.class)).isNull();
        assertThat(synchronization.cursor("FLOOD").pollRunId()).isEqualTo(initial.pollRunId());
        process("FLOOD", update);
        assertThat(count("disruption_event")).isEqualTo(2);
        assertThat(count("planning_outbox")).isEqualTo(2);
    }

    @Test
    void cancellationDoesNotCrossLiveAndSimulationScopes() {
        var flood = fact("FLOOD", "shared-key", "OBSERVED", null, null, List.of());
        process("FLOOD", poll("FLOOD", NOW, "SUCCESS", true, null, List.of(flood)));
        UUID run = UUID.randomUUID();
        clock.set(NOW.plusSeconds(1));
        process("FLOOD", poll("FLOOD", clock.instant(), "SUCCESS", true, run, List.of(flood)));
        clock.set(NOW.plusSeconds(2));
        process("FLOOD", poll("FLOOD", clock.instant(), "SUCCESS", true, null,
                List.of(fact("FLOOD", "cancel-live", "CANCEL", null, null, List.of("shared-key")))));
        assertThat(jdbc.queryForObject("SELECT status FROM disruption.hazard_state WHERE classification='OBSERVATION_ONLY'", String.class))
                .isEqualTo("CLEARED");
        assertThat(jdbc.queryForObject("SELECT status FROM disruption.hazard_state WHERE classification='SIMULATION'", String.class))
                .isEqualTo("ACTIVE");
        assertThat(count("hazard_state")).isEqualTo(2);
    }

    @Test
    void clearedOperationalStateIsPurgedOnlyAfterReplayWindowAndHistoryRemainsReadable() {
        process("FLOOD", poll("FLOOD", NOW, "SUCCESS", true, null,
                List.of(fact("FLOOD", "retained-source", "OBSERVED", null, null, List.of()))));
        var disruptionId = jdbc.queryForObject("SELECT disruption_id FROM disruption.hazard_state", UUID.class);
        clock.set(NOW.plusSeconds(1));
        process("FLOOD", poll("FLOOD", clock.instant(), "SUCCESS", true, null,
                List.of(fact("FLOOD", "cancel-retained", "CANCEL", null, null, List.of("retained-source")))));
        jdbc.update("UPDATE disruption.planning_outbox SET status='DELIVERED', delivered_at=created_at, lease_until=NULL");

        clock.set(NOW.plus(Duration.ofDays(31)));
        assertThat(retention.purgeExpiredOperationalState()).isPositive();

        assertThat(count("hazard_state")).isZero();
        assertThat(count("disruption_event")).isEqualTo(2);
        var historical = hazardReads.find(disruptionId);
        assertThat(historical.status()).isEqualTo("CLEARED");
        assertThat(historical.inactiveReason()).isEqualTo("SOURCE_CANCELLED");
        assertThat(historical.facts().sourceEventKey()).isEqualTo("retained-source");
    }

    @Test
    void exposedDemoBusProgressCreatesDurableReassessmentOnlyOncePerEvidenceAndProgress() {
        var runId = UUID.randomUUID();
        process("FLOOD", poll("FLOOD", NOW, "SUCCESS", true, null,
                List.of(fact("FLOOD", "reassessed-source", "OBSERVED", null, null, List.of()))));

        selections.select("DEMO", runId);
        when(transit.associationRadiusKm()).thenReturn(1.0);
        when(transit.route("190")).thenReturn(new HazardTransitPort.Route("190", List.of(
                new HazardTransitPort.Operator("Operator", List.of(new HazardTransitPort.Direction(1, List.of(
                        new HazardTransitPort.Stop("44251", "Demo Road", "Demo Road", null, null, 10))))))));
        when(transit.fleet(org.mockito.ArgumentMatchers.eq(runId), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(invocation -> fleet(runId, 5));

        assertThat(reassessment.enqueueExposedBuses()).isEqualTo(1);
        assertThat(reassessment.enqueueExposedBuses()).isZero();
        assertThat(count("disruption_event")).isEqualTo(2);
        assertThat(count("planning_outbox")).isEqualTo(2);

        when(transit.fleet(org.mockito.ArgumentMatchers.eq(runId), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(invocation -> fleet(runId, 6));
        assertThat(reassessment.enqueueExposedBuses()).isEqualTo(1);
        assertThat(count("disruption_event")).isEqualTo(3);
        assertThat(count("planning_outbox")).isEqualTo(3);

        var reassessmentEvents = jdbc.query("""
                SELECT id::text, evidence->'hazardSnapshot'->>'targetBusId' AS bus_id
                FROM disruption.disruption_event WHERE action='REASSESSED' ORDER BY id
                """, (rs, row) -> rs.getString("id") + ":" + rs.getString("bus_id"));
        assertThat(reassessmentEvents).hasSize(2);
        assertThat(reassessmentEvents).allSatisfy(event -> assertThat(event).contains("demo-190-b"));
        assertThat(reassessmentEvents).doesNotHaveDuplicates();
        assertThat(jdbc.queryForList("SELECT payload_json FROM disruption.planning_outbox WHERE event_id IN "
                + "(SELECT id FROM disruption.disruption_event WHERE action='REASSESSED')", String.class))
                .allSatisfy(payload -> assertThat(payload).contains("\"action\":\"REASSESSED\"",
                        "\"targetBusId\":\"demo-190-b\"", "\"targetRunId\":\"" + runId + "\""));
    }

    private HazardTransitPort.Fleet fleet(UUID runId, int progressSequence) {
        return new HazardTransitPort.Fleet(runId, "ACTIVE", List.of(new HazardTransitPort.Bus("demo-190-b", "trip-1",
                "SYNTHETIC", "190", 1, null, "ASSIGNED", new HazardTransitPort.Progress("44251",
                progressSequence, NOW, 40, List.of(), List.of()))), 1, 0, 100);
    }

    private void process(String kind, HazardPollPort.Poll poll) {
        synchronization.process(kind, poll, FRESHNESS, 2, ABSENCE_PERIOD);
    }
    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM disruption." + table, Integer.class);
    }
    private String state(String key, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM disruption.hazard_state WHERE source_event_key=?", String.class, key);
    }
    private int absences(String key) {
        return jdbc.queryForObject("SELECT absence_count FROM disruption.hazard_state WHERE source_event_key=?", Integer.class, key);
    }
    private HazardPollPort.Poll poll(String kind, Instant at, String outcome, boolean complete, UUID run,
                                     List<HazardPollPort.Fact> facts) {
        return new HazardPollPort.Poll(UUID.randomUUID(), kind + "_DATAMALL", kind, outcome, complete,
                run == null ? "OBSERVATION_ONLY" : "SIMULATION", run, at, facts.stream()
                .map(fact -> new HazardPollPort.Observation(UUID.randomUUID(), UUID.randomUUID(), fact, at)).toList());
    }
    private HazardPollPort.Fact fact(String kind, String key, String action, Instant from, Instant until, List<String> references) {
        return new HazardPollPort.Fact(kind, key, action, NOW, from, until, "Synthetic hazard", "Reported conditions",
                "Severe", "Avoid the affected area", references, null, null, "Actual", null,
                List.of(new HazardPollPort.Location("ROAD", "Demo Road", "Demo Road", null, null, null,
                        "ROAD_NAME", "PROVIDER_SUPPLIED")));
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
