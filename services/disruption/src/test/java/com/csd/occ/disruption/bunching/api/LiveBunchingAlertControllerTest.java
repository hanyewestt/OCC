package com.csd.occ.disruption.bunching.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.csd.occ.disruption.bunching.domain.AlertEvent;
import com.csd.occ.disruption.bunching.domain.LiveBunchingAlertService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(LiveBunchingAlertController.class)
class LiveBunchingAlertControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean LiveBunchingAlertService alerts;

    @Test
    void currentOpenReturnsOpeningEventForAnActiveLiveEpisode() throws Exception {
        UUID alertId = UUID.randomUUID();
        UUID rawObservationId = UUID.randomUUID();
        UUID pollRunId = UUID.randomUUID();
        AlertEvent event = new AlertEvent(alertId, "OPENED", "190", "44251", 1, rawObservationId, pollRunId,
                Instant.parse("2026-09-26T05:00:00Z"), Instant.parse("2026-09-26T05:00:01Z"), "{\"gap\":45}");
        when(alerts.currentOpen(any(Integer.class), any(Integer.class))).thenReturn(new PageImpl<>(List.of(event)));

        mvc.perform(get("/api/v1/disruption/bunching-alerts").param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].alertId").value(alertId.toString()))
                .andExpect(jsonPath("$.items[0].action").value("OPENED"))
                .andExpect(jsonPath("$.items[0].classification").value("OBSERVATION_ONLY"))
                .andExpect(jsonPath("$.items[0].rawObservationId").value(rawObservationId.toString()))
                .andExpect(jsonPath("$.items[0].pollRunId").value(pollRunId.toString()));

        verify(alerts).currentOpen(0, 200);
    }
}
