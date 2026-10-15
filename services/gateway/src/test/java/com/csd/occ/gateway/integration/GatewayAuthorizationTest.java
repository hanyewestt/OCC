package com.csd.occ.gateway.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.TestConstructor;

@SpringBootTest(properties = {
        "OCC_GATEWAY_INTERNAL_PORT=0",
        "OCC_INGESTION_INTERNAL_URL=http://127.0.0.1:1",
        "OCC_DISRUPTION_INTERNAL_URL=http://127.0.0.1:1",
        "OCC_PLANNING_INTERNAL_URL=http://127.0.0.1:1",
        "SUPABASE_PROJECT_URL=https://example.supabase.co",
        "SUPABASE_JWKS_URL=https://example.supabase.co/auth/v1/.well-known/jwks.json",
        "SUPABASE_JWT_AUDIENCE=authenticated",
        "SUPABASE_JWT_CLOCK_SKEW=30s",
        "SUPABASE_JWT_ALLOWED_ALGORITHMS=ES256",
        "OCC_GATEWAY_AUTH_TEST_ENABLED=true"
})
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class GatewayAuthorizationTest {
    private final MockMvc mvc;

    GatewayAuthorizationTest(MockMvc mvc) {
        this.mvc = mvc;
    }

    @Test
    void rejectsApiRequestsWithoutBearerAuthentication() throws Exception {
        mvc.perform(get("/api/v1/disruption/not-configured"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deniesDriverRoleAtThePhaseOneApiBoundary() throws Exception {
        mvc.perform(get("/api/v1/disruption/not-configured")
                        .with(jwt().jwt(token -> token.claim("occ_role", "DRIVER"))
                                .authorities(new SimpleGrantedAuthority("ROLE_DRIVER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void deniesAValidTokenWithNoOCCRoleAtThePhaseOneApiBoundary() throws Exception {
        mvc.perform(get("/api/v1/disruption/not-configured")
                        .with(jwt().jwt(token -> token.subject("unassigned-user")).authorities(java.util.List.of())))
                .andExpect(status().isForbidden());
    }

    @Test
    void deniesPeerEndpointsAtThePublicGateway() throws Exception {
        mvc.perform(get("/internal/v1/ingestion/arrival-snapshots")
                        .with(jwt().jwt(token -> token.claim("occ_role", "ADMIN"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void exposesTheTemporarySupabaseTestPageWithoutAuthentication() throws Exception {
        mvc.perform(get("/auth-test/index.html"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentType()).startsWith("text/html"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("Temporary local test page"));
    }
}
