package com.csd.occ.ingestion.api;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.csd.occ.ingestion.domain.BunchingFeedService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(BunchingSnapshotController.class)
class BunchingSnapshotControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean BunchingFeedService feed;

    @Test
    void snapshotsReturnTheCursorAndNormalizedArrivals() throws Exception {
        UUID raw = UUID.randomUUID();
        UUID sourcePollCycle = UUID.randomUUID();
        when(feed.page(4, 100)).thenReturn(new BunchingFeedService.FeedPage(
                List.of(snapshot(raw, sourcePollCycle)), 5));

        mvc.perform(get("/internal/v1/arrival-snapshots").param("afterSequence", "4"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nextSequence").value(5))
                .andExpect(jsonPath("$.items[0].rawObservationId").value(raw.toString()))
                .andExpect(jsonPath("$.items[0].sourcePollCycleId").value(sourcePollCycle.toString()))
                .andExpect(jsonPath("$.items[0].normalizationState").value("NORMALIZED"))
                .andExpect(jsonPath("$.items[0].arrivals[0].operator").value("SMRT"))
                .andExpect(jsonPath("$.items[0].arrivals[0].visitNumber").value(1))
                .andExpect(jsonPath("$.items[0].arrivals[0].routeStopSequence").value(12));
    }

    @Test
    void missingSnapshotReturnsNotFound() throws Exception {
        UUID raw = UUID.randomUUID();
        when(feed.snapshot(raw)).thenReturn(Optional.empty());

        mvc.perform(get("/internal/v1/arrival-snapshots/{rawObservationId}", raw)).andExpect(status().isNotFound());
    }

    @Test
    void snapshotsExposeUnavailableCorrelationEvidenceAsNull() throws Exception {
        UUID raw = UUID.randomUUID();
        var ambiguous = new BunchingFeedService.Snapshot(5, raw, UUID.randomUUID(), null, "stop:03239",
                Instant.parse("2026-09-25T00:00:00Z"), "NORMALIZED",
                List.of(new BunchingFeedService.Arrival(UUID.randomUUID(), UUID.randomUUID(), "03239", "972",
                        "SMRT", 1, 1, null, Instant.parse("2026-09-25T00:05:00Z"), "03239", "03239",
                        new java.math.BigDecimal("1.300000"), new java.math.BigDecimal("103.800000"), null)));
        when(feed.page(4, 100)).thenReturn(new BunchingFeedService.FeedPage(List.of(ambiguous), 5));

        mvc.perform(get("/internal/v1/arrival-snapshots").param("afterSequence", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].sourcePollCycleId").value(nullValue()))
                .andExpect(jsonPath("$.items[0].arrivals[0].visitNumber").value(nullValue()))
                .andExpect(jsonPath("$.items[0].arrivals[0].routeStopSequence").value(nullValue()));
    }

    private BunchingFeedService.Snapshot snapshot(UUID raw, UUID sourcePollCycle) {
        return new BunchingFeedService.Snapshot(5, raw, UUID.randomUUID(), sourcePollCycle, "stop:44009",
                Instant.parse("2026-09-25T00:00:00Z"), "NORMALIZED",
                List.of(new BunchingFeedService.Arrival(UUID.randomUUID(), UUID.randomUUID(), "44009", "190",
                        "SMRT", 1, 1, 1, Instant.parse("2026-09-25T00:05:00Z"), "44009", "02089",
                        new java.math.BigDecimal("1.300000"), new java.math.BigDecimal("103.800000"), 12)));
    }
}
