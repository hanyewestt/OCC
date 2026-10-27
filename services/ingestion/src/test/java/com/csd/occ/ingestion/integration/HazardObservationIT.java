package com.csd.occ.ingestion.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import com.csd.occ.ingestion.domain.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = {"occ.datamall.account-key=synthetic-key", "occ.ingestion.import-reference-on-startup=false",
        "occ.ingestion.seed-targets-on-startup=false", "occ.ingestion.schedule.bus-arrival-enabled=false",
        "occ.ingestion.schedule.traffic-incident-enabled=false"})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class HazardObservationIT {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("occ").withUsername("occ_admin").withPassword("synthetic-admin")
            .withInitScript("db/ingestion-test-schema.sql");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "occ_ingestion");
        registry.add("spring.datasource.password", () -> "synthetic-ingestion");
    }
    @MockitoBean HazardFeedPort provider;
    @MockitoBean com.csd.occ.ingestion.infrastructure.config.PacingSchedule pacing;
    @Autowired HazardPollingService polling;
    @Autowired HazardPollFeedService feed;
    @Autowired HazardObservationRepository hazards;
    @Autowired TrafficIncidentObservationRepository traffic;
    @Autowired RawObservationRepository raw;
    @Autowired HazardEvidencePort evidence;
    @Autowired JdbcTemplate jdbc;

    @Test
    void roundTripsTypedFactsAndLongDescriptionsWithoutLeakingIntoTrafficReads() {
        var original = DemoHazardScenario.observations(0, Instant.EPOCH, Instant.EPOCH).getFirst();
        var fact = new HazardFeedPort.Fact(original.kind(), original.sourceEventKey(), original.sourceAction(),
                original.reportedAt(), original.effectiveFrom(), original.effectiveUntil(), original.title(), "flood ".repeat(200),
                original.severity(), original.instruction(), original.references(), original.line(), original.direction(),
                original.serviceStatus(), original.department(), original.locations());
        when(provider.fetch(HazardKind.FLOOD)).thenReturn(new HazardFeedPort.Batch(200,
                List.of("{\"value\":[]}", "{ \"value\": [] }"), List.of(fact), List.of("synthetic incomplete page")));
        polling.poll(HazardKind.FLOOD, Duration.ofMinutes(10));
        var result = feed.page(HazardKind.FLOOD, null, null, 20);
        assertThat(result.polls()).hasSize(1);
        assertThat(result.polls().getFirst().complete()).isFalse();
        assertThat(result.polls().getFirst().observations().getFirst().facts()).isEqualTo(fact);
        assertThat(hazards.findAll()).hasSize(1);
        assertThat(traffic.findAll()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ingestion.traffic_incident_observation", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ingestion.hazard_observation", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = "
                + "'ingestion' AND table_name = 'traffic_incident_observation' AND column_name = 'hazard_kind'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_schema = "
                + "'ingestion' AND table_name = 'traffic_incident_observation' AND column_name = 'latitude'",
                String.class)).isEqualTo("NO");
        var stored = raw.findAll().getFirst();
        var decoded = tools.jackson.databind.json.JsonMapper.builder().build()
                .readValue(stored.getPayload(), String[].class);
        assertThat(evidence.checksum(evidence.encodePages(List.of(decoded)))).isEqualTo(stored.getPayloadChecksum());
        assertThat(feed.page(HazardKind.FLOOD, result.polls().getFirst().completedAt(),
                result.polls().getFirst().pollRunId(), 20).polls()).isEmpty();
    }
}
