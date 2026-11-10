package com.csd.occ.ingestion.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.csd.occ.ingestion.domain.DemoAlertDetails;
import com.csd.occ.ingestion.domain.DemoRunService;
import com.csd.occ.ingestion.domain.DemoVehicleDetails;
import com.csd.occ.ingestion.infrastructure.security.IngestionSecurityConfig;
import com.csd.occ.ingestion.infrastructure.security.SupabaseRoleConverter;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value = SimulationBunchingGroupController.class, properties = {
        "occ.security.supabase.project-url=https://issuer.test", "occ.security.supabase.jwks-url=https://issuer.test/jwks",
        "occ.security.supabase.audience=authenticated", "occ.security.supabase.clock-skew=30s",
        "occ.security.supabase.allowed-algorithms=ES256", "occ.security.peer.disruption-to-ingestion-token=disruption-token",
        "occ.security.peer.planning-to-ingestion-token=planning-token"})
@Import({IngestionSecurityConfig.class, SupabaseRoleConverter.class})
class SimulationBunchingGroupControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean DemoRunService runs;
    @MockitoBean JwtDecoder decoder;

    @ParameterizedTest
    @CsvSource({"planning-token,200", "disruption-token,401", "invalid-token,401"})
    void onlyPlanningPeerCanReadResolvedGroup(String token, int expected) throws Exception {
        var first = new DemoVehicleDetails("REG-1", "DEMO", "DD", "CAP-1", "bus-1");
        var second = new DemoVehicleDetails("REG-2", "DEMO", "DD", "CAP-2", "bus-2");
        when(runs.bunchingGroup(any(), any())).thenReturn(Optional.of(new DemoAlertDetails(UUID.randomUUID(),
                UUID.randomUUID(), "OPENED", "SIMULATION", "190", "44251", null, null, 1,
                Instant.parse("2026-11-10T00:00:00Z"), 45L, first, second, List.of(first, second), "{}")));
        var result = mvc.perform(get("/internal/v1/ingestion/demo-runs/{run}/bunching-groups/{event}",
                        UUID.randomUUID(), UUID.randomUUID()).header("X-OCC-Peer-Token", token))
                .andExpect(status().is(expected));
        if (expected == 200) result.andExpect(jsonPath("$.affectedBusReferences[0]").value("bus-1"))
                .andExpect(jsonPath("$.followingBusReference").value("bus-2"));
    }
}
