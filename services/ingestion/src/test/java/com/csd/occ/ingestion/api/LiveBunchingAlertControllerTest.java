package com.csd.occ.ingestion.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.csd.occ.ingestion.domain.BunchingControlPort;
import com.csd.occ.ingestion.domain.LiveBunchingAlert;
import com.csd.occ.ingestion.domain.LiveBunchingAlertPage;
import com.csd.occ.ingestion.domain.DemoSimulationArrivalDetails;
import com.csd.occ.ingestion.domain.BusArrivalObservationRepository;
import java.util.Optional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(LiveBunchingAlertController.class)
class LiveBunchingAlertControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean BunchingControlPort bunching;
    @MockitoBean BusArrivalObservationRepository arrivals;

    @Test
    void currentOpenProxiesCurrentLiveAlertsForTheDemoPage() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID alertId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID busOneId = UUID.randomUUID();
        UUID busTwoId = UUID.randomUUID();
        UUID busOneNextObservationId = UUID.randomUUID();
        LiveBunchingAlert alert = new LiveBunchingAlert(eventId, alertId, "OPENED", "OBSERVATION_ONLY", "190",
                "44251", 1, Instant.parse("2026-09-26T05:00:01Z"), Instant.parse("2026-09-26T05:00:00Z"),
                UUID.randomUUID(), UUID.randomUUID(), "{\"gap\":45}", List.of(busOneId, busTwoId, busOneNextObservationId), runId);
        when(arrivals.findSimulationArrivalDetails(runId, busOneId)).thenReturn(Optional.of(
                new DemoSimulationArrivalDetails("DEMO-01", "DEMO", "DD", "CAP-1", Instant.EPOCH)));
        when(arrivals.findSimulationArrivalDetails(runId, busTwoId)).thenReturn(Optional.of(
                new DemoSimulationArrivalDetails("DEMO-02", "DEMO", "DD", "CAP-2", Instant.EPOCH)));
        when(arrivals.findSimulationArrivalDetails(runId, busOneNextObservationId)).thenReturn(Optional.of(
                new DemoSimulationArrivalDetails("DEMO-01", "DEMO", "DD", "CAP-OTHER", Instant.EPOCH.plusSeconds(30))));
        when(bunching.currentLiveAlerts(0, 200)).thenReturn(new LiveBunchingAlertPage(List.of(alert), 0, 200, 1, 1));

        mvc.perform(get("/api/v1/live-bunching-alerts").param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.items[0].alertId").value(alertId.toString()))
                .andExpect(jsonPath("$.items[0].classification").value("OBSERVATION_ONLY"))
                .andExpect(jsonPath("$.items[0].affectedObservationIds[0]").value(busOneId.toString()))
                .andExpect(jsonPath("$.items[0].affectedObservationIds.length()").value(3))
                .andExpect(jsonPath("$.items[0].affectedVehicles.length()").value(2))
                .andExpect(jsonPath("$.items[0].affectedVehicles[0].registrationNumber").value("DEMO-01"))
                .andExpect(jsonPath("$.items[0].affectedVehicles[1].registrationNumber").value("DEMO-02"));

        verify(bunching).currentLiveAlerts(0, 200);
    }
}
