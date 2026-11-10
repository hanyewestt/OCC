package com.csd.occ.ingestion.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.csd.occ.ingestion.domain.FrontendConfig;
import com.csd.occ.ingestion.infrastructure.security.IngestionSecurityConfig;
import com.csd.occ.ingestion.infrastructure.security.SupabaseRoleConverter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value = FrontendConfigController.class, properties = {
        "occ.security.supabase.project-url=https://issuer.test",
        "occ.security.supabase.jwks-url=https://issuer.test/jwks",
        "occ.security.supabase.audience=authenticated",
        "occ.security.supabase.clock-skew=30s",
        "occ.security.supabase.allowed-algorithms=ES256",
        "occ.security.peer.disruption-to-ingestion-token=disruption-token",
        "occ.security.peer.planning-to-ingestion-token=planning-token"})
@Import({IngestionSecurityConfig.class, SupabaseRoleConverter.class, FrontendConfigControllerTest.Config.class})
class FrontendConfigControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;

    @Test
    void exposesPublicFrontendConfigurationWithoutAuthentication() throws Exception {
        mvc.perform(get("/frontend-config"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.projectUrl").value("https://example.supabase.co"))
                .andExpect(jsonPath("$.publishableKey").value("sb_publishable_fixture"))
                .andExpect(jsonPath("$.controllerRefreshIntervalSeconds").value(30))
                .andExpect(jsonPath("$.ingestionPollIntervalSeconds").value(60))
                .andExpect(jsonPath("$.routeIncidentGapToleranceSeconds").value(20))
                .andExpect(jsonPath("$.captainRefreshIntervalSeconds").value(5))
                .andExpect(jsonPath("$.publicNoticeRefreshIntervalSeconds").value(30))
                .andExpect(content().contentTypeCompatibleWith("application/json"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        FrontendConfig frontendConfig() {
            return new FrontendConfig("https://example.supabase.co", "sb_publishable_fixture", 30, 60, 20, 5, 30);
        }
    }
}
