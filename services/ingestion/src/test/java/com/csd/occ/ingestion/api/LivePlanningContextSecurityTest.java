package com.csd.occ.ingestion.api;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.csd.occ.ingestion.domain.RootCauseContextService;
import com.csd.occ.ingestion.domain.RootCauseContextSnapshot;
import com.csd.occ.ingestion.infrastructure.security.IngestionSecurityConfig;
import com.csd.occ.ingestion.infrastructure.security.SupabaseRoleConverter;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value = RootCauseContextController.class, properties = {
        "occ.security.supabase.project-url=https://issuer.test", "occ.security.supabase.jwks-url=https://issuer.test/jwks",
        "occ.security.supabase.audience=authenticated", "occ.security.supabase.clock-skew=30s",
        "occ.security.supabase.allowed-algorithms=ES256", "occ.security.peer.disruption-to-ingestion-token=disruption-token",
        "occ.security.peer.planning-to-ingestion-token=planning-token"})
@Import({IngestionSecurityConfig.class, SupabaseRoleConverter.class})
class LivePlanningContextSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean RootCauseContextService contexts;
    @MockitoBean JwtDecoder decoder;

    @ParameterizedTest
    @CsvSource({"planning-token,200", "disruption-token,401", "invalid-token,401"})
    void onlyThePlanningPeerCanReadLiveEvidence(String token, int expected) throws Exception {
        when(contexts.findLive(any(), any(), any(), any(), anyList())).thenReturn(
                new RootCauseContextSnapshot("OBSERVATION_ONLY", null, null, List.of(), List.of(), List.of()));
        mvc.perform(get("/internal/v1/ingestion/planning-context")
                        .header("X-OCC-Peer-Token", token).param("at", "2026-10-24T00:00:00Z")
                        .param("serviceId", "190").param("stopCode", "44009").param("directionId", "1")
                        .param("arrivalObservationIds", UUID.randomUUID().toString(), UUID.randomUUID().toString()))
                .andExpect(status().is(expected));
    }
}
