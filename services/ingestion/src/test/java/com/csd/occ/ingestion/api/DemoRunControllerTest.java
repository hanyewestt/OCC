package com.csd.occ.ingestion.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.csd.occ.ingestion.domain.DemoAlertDetails;
import com.csd.occ.ingestion.domain.DemoBunchingUnavailableException;
import com.csd.occ.ingestion.domain.DemoRunAlreadyActiveException;
import com.csd.occ.ingestion.domain.DemoRunDetails;
import com.csd.occ.ingestion.domain.DemoRunNotFoundException;
import com.csd.occ.ingestion.domain.DemoRecommendationDetails;
import com.csd.occ.ingestion.domain.DemoRecommendationImpact;
import com.csd.occ.ingestion.domain.DemoRunService;
import com.csd.occ.ingestion.domain.DemoVehicleDetails;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DemoRunController.class)
class DemoRunControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean DemoRunService runs;

    @Test
    void startCreatesOneRunAndReturnsItsId() throws Exception {
        UUID runId = UUID.randomUUID();
        when(runs.start()).thenReturn(new DemoRunDetails(runId, "RUNNING", 0, List.of()));

        mvc.perform(post("/api/v1/demo-runs"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(runId.toString()))
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void statusReturnsOnlyTheRequestedRunsSimulationAlertDetails() throws Exception {
        UUID runId = UUID.randomUUID();
        var leading = new DemoVehicleDetails("DEMO-190-01", "DEMO-OPERATOR", "DD", "DEMO-CAPTAIN-01");
        var following = new DemoVehicleDetails("DEMO-190-02", "DEMO-OPERATOR", "DD", "DEMO-CAPTAIN-02");
        var alert = new DemoAlertDetails(UUID.randomUUID(), UUID.randomUUID(), "OPENED", "SIMULATION", "190", "44251", "Teck Whye Secondary School",
                "Teck Whye Ave", 1, Instant.parse("2026-09-26T05:00:00Z"), 45L, leading, following);
        when(runs.status(runId)).thenReturn(new DemoRunDetails(runId, "REPLAY_COMPLETE", 3, List.of(alert)));

        mvc.perform(get("/api/v1/demo-runs/{id}", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts.length()").value(1))
                .andExpect(jsonPath("$.alerts[0].classification").value("SIMULATION"))
                .andExpect(jsonPath("$.alerts[0].action").value("OPENED"))
                .andExpect(jsonPath("$.alerts[0].serviceId").value("190"))
                .andExpect(jsonPath("$.alerts[0].stopCode").value("44251"))
                .andExpect(jsonPath("$.alerts[0].stopName").value("Teck Whye Secondary School"))
                .andExpect(jsonPath("$.alerts[0].stopRoadName").value("Teck Whye Ave"))
                .andExpect(jsonPath("$.alerts[0].directionId").value(1))
                .andExpect(jsonPath("$.alerts[0].headwaySeconds").value(45))
                .andExpect(jsonPath("$.alerts[0].leadingVehicle.registrationNumber").value("DEMO-190-01"))
                .andExpect(jsonPath("$.alerts[0].leadingVehicle.operator").value("DEMO-OPERATOR"))
                .andExpect(jsonPath("$.alerts[0].leadingVehicle.captainCode").value("DEMO-CAPTAIN-01"))
                .andExpect(jsonPath("$.alerts[0].followingVehicle.registrationNumber").value("DEMO-190-02"));
    }

    @Test
    void closeReturnsTheClosedRun() throws Exception {
        UUID runId = UUID.randomUUID();
        when(runs.close(runId)).thenReturn(new DemoRunDetails(runId, "CLOSED", 3, List.of()));

        mvc.perform(post("/api/v1/demo-runs/{id}/close", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(runId.toString()))
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void recommendationReturnsTheSimulationOutputWhenReady() throws Exception {
        UUID runId = UUID.randomUUID();
        when(runs.recommendation(runId)).thenReturn(java.util.Optional.of(new DemoRecommendationDetails(
                UUID.randomUUID(), "SIMULATION", "RECOMMENDED", "HOLD", "SBS5898U", "44241", 120,
                "Hold the following bus.", "Vehicle breakdown.", new BigDecimal("0.771"),
                List.of("Additional delay."), List.of(), new DemoRecommendationImpact(45, 165, 38, 24,
                new BigDecimal("190"), new BigDecimal("128")))));

        mvc.perform(get("/api/v1/demo-runs/{id}/recommendation", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classification").value("SIMULATION"))
                .andExpect(jsonPath("$.mitigationType").value("HOLD"))
                .andExpect(jsonPath("$.expectedImpact.headwayAfterSeconds").value(165));
    }

    @Test
    void duplicateStartReturnsConflictWithTheActiveRunId() throws Exception {
        UUID activeRunId = UUID.randomUUID();
        when(runs.start()).thenThrow(new DemoRunAlreadyActiveException(activeRunId));

        mvc.perform(post("/api/v1/demo-runs"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.activeRunId").value(activeRunId.toString()));
    }

    @Test
    void unknownRunReturnsNotFoundProblem() throws Exception {
        UUID missingRunId = UUID.randomUUID();
        when(runs.status(missingRunId)).thenThrow(new DemoRunNotFoundException(missingRunId));

        mvc.perform(get("/api/v1/demo-runs/{id}", missingRunId))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void unavailableBunchingControlReturnsServiceUnavailableProblem() throws Exception {
        when(runs.start()).thenThrow(new DemoBunchingUnavailableException());

        mvc.perform(post("/api/v1/demo-runs"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void unavailableBunchingControlDuringCloseKeepsRetryableServiceUnavailableResponse() throws Exception {
        UUID runId = UUID.randomUUID();
        when(runs.close(runId)).thenThrow(new DemoBunchingUnavailableException());

        mvc.perform(post("/api/v1/demo-runs/{id}/close", runId))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }
}
