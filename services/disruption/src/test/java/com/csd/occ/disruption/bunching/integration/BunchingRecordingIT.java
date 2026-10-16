package com.csd.occ.disruption.bunching.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.csd.occ.disruption.bunching.domain.ArrivalSnapshot;
import com.csd.occ.disruption.bunching.domain.BunchingRecordingService;
import com.csd.occ.disruption.bunching.domain.RecordedBunching;
import com.csd.occ.disruption.bunching.domain.SourceProcessingStateRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@ActiveProfiles("test")
@SpringBootTest(properties = {"occ.disruption.consumer-enabled=false", "occ.disruption.planning.worker-enabled=false",
        "spring.datasource.url=jdbc:postgresql://unused/test", "spring.datasource.password=synthetic-test-only"})
@Testcontainers(disabledWithoutDocker = true)
@Import(BunchingRecordingIT.FixedTime.class)
class BunchingRecordingIT {
    private static final Instant NOW = Instant.parse("2026-09-22T04:00:00Z");
    private static final Duration THRESHOLD = Duration.ofSeconds(90);

    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine")
            .withInitScript("db/disruption-schema.sql");

    @Autowired BunchingRecordingService service;
    @Autowired SourceProcessingStateRepository states;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach void reset() {
        jdbc.execute("TRUNCATE disruption.planning_outbox, disruption.event_evidence, disruption.disruption_event, disruption.traffic_incident_state, disruption.disruption_processing_state, disruption.disruption, disruption.source_processing_state");
        jdbc.update("UPDATE disruption.consumer_state SET mode='LIVE', demo_run_id=NULL, last_sequence=0, updated_at=? WHERE id=1", Timestamp.from(NOW));
    }

    @Test void recordsOnlyTheCompactProcessedResult() {
        var snapshot = snapshot(60);
        var result = service.record(snapshot, THRESHOLD, Duration.ofSeconds(120));
        var stored = states.findById(snapshot.rawObservationId()).orElseThrow();
        assertEquals(RecordedBunching.Outcome.RECORDED, result.outcome());
        assertEquals("PROCESSED", stored.status());
        assertEquals(NOW, stored.evaluatedAt());
        assertEquals(1, stored.bunchedGroupCount());
        assertEquals(0, BigDecimal.valueOf(90).compareTo(stored.thresholdSeconds()));
    }

    @Test void duplicateSourceUsesTheExistingProcessedResult() {
        var snapshot = snapshot(60);
        assertEquals(RecordedBunching.Outcome.RECORDED, service.record(snapshot, THRESHOLD, Duration.ofSeconds(120)).outcome());
        assertEquals(RecordedBunching.Outcome.ALREADY_RECORDED, service.record(snapshot, Duration.ofSeconds(30), Duration.ofSeconds(120)).outcome());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM disruption.source_processing_state", Integer.class));
    }

    @Test void rollbackDoesNotPersistProcessingState() {
        var snapshot = snapshot(60);
        var transaction = new TransactionTemplate(transactions);
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            service.record(snapshot, THRESHOLD, Duration.ofSeconds(120));
            throw new IllegalStateException("synthetic rollback");
        }));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM disruption.source_processing_state", Integer.class));
    }

    @Test void deferredSourceCanBeProcessedAndReplayedAfterRestart() {
        var snapshot = snapshot(60);
        states.save(new com.csd.occ.disruption.bunching.domain.SourceProcessingState(snapshot.rawObservationId(), NOW, null));
        assertEquals(RecordedBunching.Outcome.RECORDED, service.record(snapshot, THRESHOLD, Duration.ofSeconds(120)).outcome());
        assertEquals("PROCESSED", states.findById(snapshot.rawObservationId()).orElseThrow().status());
        assertEquals(RecordedBunching.Outcome.ALREADY_RECORDED, service.record(snapshot, THRESHOLD, Duration.ofSeconds(120)).outcome());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM disruption.disruption_event", Integer.class));
    }

    private ArrivalSnapshot snapshot(int gap) {
        return new ArrivalSnapshot(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), NOW,
                List.of(new ArrivalSnapshot.Arrival(UUID.randomUUID(), "190", "44009", Optional.of(1), 1, Optional.of(NOW.plusSeconds(300))),
                        new ArrivalSnapshot.Arrival(UUID.randomUUID(), "190", "44009", Optional.of(1), 2, Optional.of(NOW.plusSeconds(300 + gap)))));
    }

    @TestConfiguration static class FixedTime {
        @Bean @Primary Clock testClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }
}
