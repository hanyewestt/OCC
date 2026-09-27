package com.csd.occ.ingestion.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.csd.occ.ingestion.domain.BunchingControlPort;
import com.csd.occ.ingestion.domain.LiveBunchingAlert;
import com.csd.occ.ingestion.domain.LiveBunchingAlertPage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value = LiveBunchingAlertController.class, properties = "occ.ingestion.demo.enabled=true")
class LiveBunchingAlertControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean BunchingControlPort bunching;

    @Test
    void currentOpenProxiesCurrentLiveAlertsForTheDemoPage() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID alertId = UUID.randomUUID();
        LiveBunchingAlert alert = new LiveBunchingAlert(eventId, alertId, "OPENED", "OBSERVATION_ONLY", "190",
                "44251", 1, Instant.parse("2026-09-26T05:00:01Z"), Instant.parse("2026-09-26T05:00:00Z"),
                UUID.randomUUID(), UUID.randomUUID(), "{\"gap\":45}");
        when(bunching.currentLiveAlerts(0, 200)).thenReturn(new LiveBunchingAlertPage(List.of(alert), 0, 200, 1, 1));

        mvc.perform(get("/api/v1/live-bunching-alerts").param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.items[0].alertId").value(alertId.toString()))
                .andExpect(jsonPath("$.items[0].classification").value("OBSERVATION_ONLY"));

        verify(bunching).currentLiveAlerts(0, 200);
    }
}
