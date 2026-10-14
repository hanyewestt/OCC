package com.csd.occ.disruption.bunching.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.csd.occ.disruption.bunching.domain.AlertEvent;
import com.csd.occ.disruption.bunching.domain.AlertEventRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DemoAlertController.class)
class DemoAlertControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean AlertEventRepository alerts;

    @Test
    void alertQueryUsesTheRequestedRunAndSimulationClassification() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID leading = UUID.randomUUID();
        UUID following = UUID.randomUUID();
        var event = new AlertEvent(UUID.randomUUID(), "OPENED", "190", "44251", 1,
                UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2026-09-26T05:00:00Z"),
                Instant.parse("2026-09-26T05:00:01Z"), "{}", "SIMULATION", runId, leading, following);
        when(alerts.findByDemoRunIdAndClassificationAndKindOrderByTriggeredAtDesc(runId, "SIMULATION", "BUNCHING"))
                .thenReturn(List.of(event));

        mvc.perform(get("/internal/v1/disruption/demo-runs/{demoRunId}/alerts", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts.length()").value(1))
                .andExpect(jsonPath("$.alerts[0].classification").value("SIMULATION"))
                .andExpect(jsonPath("$.alerts[0].action").value("OPENED"))
                .andExpect(jsonPath("$.alerts[0].leadingObservationId").value(leading.toString()))
                .andExpect(jsonPath("$.alerts[0].followingObservationId").value(following.toString()));

        verify(alerts).findByDemoRunIdAndClassificationAndKindOrderByTriggeredAtDesc(runId, "SIMULATION", "BUNCHING");
    }
}
