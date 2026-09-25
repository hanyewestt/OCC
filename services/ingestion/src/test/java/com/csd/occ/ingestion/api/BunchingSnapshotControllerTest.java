package com.csd.occ.ingestion.api;

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
        when(feed.page(4, 100)).thenReturn(new BunchingFeedService.FeedPage(List.of(snapshot(raw)), 5));

        mvc.perform(get("/internal/v1/arrival-snapshots").param("afterSequence", "4"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nextSequence").value(5))
                .andExpect(jsonPath("$.items[0].rawObservationId").value(raw.toString()))
                .andExpect(jsonPath("$.items[0].normalizationState").value("NORMALIZED"));
    }

    @Test
    void missingSnapshotReturnsNotFound() throws Exception {
        UUID raw = UUID.randomUUID();
        when(feed.snapshot(raw)).thenReturn(Optional.empty());

        mvc.perform(get("/internal/v1/arrival-snapshots/{rawObservationId}", raw)).andExpect(status().isNotFound());
    }

    private BunchingFeedService.Snapshot snapshot(UUID raw) {
        return new BunchingFeedService.Snapshot(5, raw, UUID.randomUUID(), "stop:44009", Instant.parse("2026-09-25T00:00:00Z"),
                "NORMALIZED", List.of(new BunchingFeedService.Arrival(UUID.randomUUID(), UUID.randomUUID(), "44009", "190",
                        1, 1, Instant.parse("2026-09-25T00:05:00Z"), null, null, null, null)));
    }
}
