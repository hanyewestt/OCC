package com.csd.occ.ingestion.api;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import com.csd.occ.ingestion.domain.DemoFleetService;
import com.csd.occ.ingestion.domain.DemoFleetSnapshot;
import com.csd.occ.ingestion.infrastructure.security.IngestionSecurityConfig;
import com.csd.occ.ingestion.infrastructure.security.SupabaseRoleConverter;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value = DemoFleetController.class, properties = {
        "occ.security.supabase.project-url=https://issuer.test", "occ.security.supabase.jwks-url=https://issuer.test/jwks",
        "occ.security.supabase.audience=authenticated", "occ.security.supabase.clock-skew=30s",
        "occ.security.supabase.allowed-algorithms=ES256", "occ.security.peer.disruption-to-ingestion-token=disruption-token",
        "occ.security.peer.planning-to-ingestion-token=planning-token"})
@Import({IngestionSecurityConfig.class, SupabaseRoleConverter.class})
class DemoFleetControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean DemoFleetService fleet;
    @MockitoBean JwtDecoder decoder;

    @ParameterizedTest
    @CsvSource({"planning-token,200", "disruption-token,200", "invalid-token,401"})
    void onlyNamedPeersCanReadAccountAssignments(String token, int expected) throws Exception {
        UUID run = UUID.randomUUID();
        when(fleet.find(eq(run), anyInt(), anyInt())).thenReturn(
                new DemoFleetSnapshot(run, "RUNNING", List.of(), 0, 0, 20));
        mvc.perform(get("/internal/v1/ingestion/demo-fleet").param("demoRunId", run.toString())
                .header("X-OCC-Peer-Token", token)).andExpect(status().is(expected));
    }

    @Test
    void captainAndControllerTokensCannotReadInternalAssignments() throws Exception {
        for (String role : List.of("CAPTAIN", "CONTROLLER", "ADMIN"))
            mvc.perform(get("/internal/v1/ingestion/demo-fleet").param("demoRunId", UUID.randomUUID().toString())
                    .with(user("user").roles(role))).andExpect(status().isForbidden());
    }
}
