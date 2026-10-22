package com.csd.occ.ingestion.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.csd.occ.ingestion.api.error.GlobalExceptionHandler;
import com.csd.occ.ingestion.domain.PacingConfigurationPort;
import com.csd.occ.ingestion.domain.PacingGap;
import com.csd.occ.ingestion.domain.PacingMetric;
import com.csd.occ.ingestion.domain.PacingService;
import com.csd.occ.ingestion.domain.PacingSnapshot;
import com.csd.occ.ingestion.domain.PacingState;
import com.csd.occ.ingestion.domain.PacingSubscriberLimitException;
import com.csd.occ.ingestion.domain.PacingUnavailableException;
import com.csd.occ.ingestion.infrastructure.security.IngestionSecurityConfig;
import com.csd.occ.ingestion.infrastructure.security.SupabaseRoleConverter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PacingSnapshotController.class)
@Import({GlobalExceptionHandler.class, IngestionSecurityConfig.class, SupabaseRoleConverter.class})
class PacingSnapshotControllerTest {
    private static final Instant NOW = Instant.parse("2026-10-21T05:00:10Z");
    private static final PacingSnapshot SNAPSHOT = new PacingSnapshot(NOW, List.of(
            metric("DEMO-BUS-190-01", Optional.empty(), Optional.of(gap(45, PacingState.TOO_CLOSE))),
            metric("DEMO-BUS-190-02", Optional.of(gap(45, PacingState.TOO_CLOSE)), Optional.empty())));

    @Autowired MockMvc mvc;
    @MockitoBean PacingService pacing;
    @MockitoBean PacingConfigurationPort configuration;

    @BeforeEach
    void settings() {
        when(configuration.streamTimeout()).thenReturn(Duration.ofMinutes(30));
    }

    @Test
    void anonymousCallersAreRejected() throws Exception {
        mvc.perform(get("/api/v1/pacing-snapshots/latest")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/pacing-snapshots/stream")).andExpect(status().isUnauthorized());
    }

    @Test
    void unassignedUsersAndTheRetiredDriverRoleCannotReadPacing() throws Exception {
        mvc.perform(get("/api/v1/pacing-snapshots/latest").with(user("old").roles("DRIVER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/pacing-snapshots/latest").with(user("new").roles("UNASSIGNED")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/pacing-snapshots/stream").with(user("new").roles("UNASSIGNED")))
                .andExpect(status().isForbidden());
    }

    @Test
    void captainsCanReadPacingButNoOtherOperationalApi() throws Exception {
        when(pacing.latest()).thenReturn(SNAPSHOT);

        mvc.perform(get("/api/v1/pacing-snapshots/latest").with(user("captain").roles("CAPTAIN")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/live-bunching-alerts").with(user("captain").roles("CAPTAIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void latestReturnsTheGapsInTheObuContractShape() throws Exception {
        when(pacing.latest()).thenReturn(SNAPSHOT);

        mvc.perform(get("/api/v1/pacing-snapshots/latest").param("vehicleReference", "DEMO-BUS-190-02")
                        .with(user("controller").roles("CONTROLLER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calculatedAt").value("2026-10-21T05:00:10Z"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].vehicleReference").value("DEMO-BUS-190-02"))
                .andExpect(jsonPath("$.items[0].serviceId").value("190"))
                .andExpect(jsonPath("$.items[0].directionId").value(1))
                .andExpect(jsonPath("$.items[0].targetHeadwaySeconds").value(180))
                .andExpect(jsonPath("$.items[0].ahead.headwaySeconds").value(45))
                .andExpect(jsonPath("$.items[0].ahead.state").value("TOO_CLOSE"))
                .andExpect(jsonPath("$.items[0].behind").isEmpty())
                .andExpect(jsonPath("$.items[0].classification").value("SIMULATION"));
    }

    @Test
    void latestBeforeTheFirstCalculationIsAProblemResponse() throws Exception {
        when(pacing.latest()).thenThrow(new PacingUnavailableException());

        mvc.perform(get("/api/v1/pacing-snapshots/latest").with(user("captain").roles("CAPTAIN")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Pacing metrics unavailable"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamPushesFilteredSnapshotsAsServerSentEvents() throws Exception {
        when(pacing.subscribe(any())).thenAnswer(invocation -> {
            invocation.<Consumer<PacingSnapshot>>getArgument(0).accept(SNAPSHOT);
            return mock(PacingService.Subscription.class);
        });

        var result = mvc.perform(get("/api/v1/pacing-snapshots/stream").param("vehicleReference", "DEMO-BUS-190-01")
                        .with(user("captain").roles("CAPTAIN")))
                .andExpect(request().asyncStarted())
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("event:pacing", "id:2026-10-21T05:00:10Z", "\"vehicleReference\":\"DEMO-BUS-190-01\"",
                "\"behind\":{\"headwaySeconds\":45,\"state\":\"TOO_CLOSE\"}");
        assertThat(body).doesNotContain("DEMO-BUS-190-02");
    }

    @Test
    void streamAtCapacityIsRefused() throws Exception {
        when(pacing.subscribe(any())).thenThrow(new PacingSubscriberLimitException(200));

        mvc.perform(get("/api/v1/pacing-snapshots/stream").with(user("captain").roles("CAPTAIN")))
                .andExpect(status().isServiceUnavailable());
    }

    private static PacingMetric metric(String reference, Optional<PacingGap> ahead, Optional<PacingGap> behind) {
        return new PacingMetric(reference, "SIMULATION", "44251", "190", 1, Duration.ofSeconds(180), ahead, behind,
                NOW.minusSeconds(10));
    }

    private static PacingGap gap(long seconds, PacingState state) {
        return new PacingGap(Duration.ofSeconds(seconds), state);
    }
}
