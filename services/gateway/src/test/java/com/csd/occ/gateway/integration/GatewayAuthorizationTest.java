package com.csd.occ.gateway.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.Files;
import java.nio.file.Path;
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
        "SUPABASE_SB_PUBLISHABLE=sb_publishable_synthetic-test"
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
    void deniesCaptainRoleAtThePhaseOneApiBoundary() throws Exception {
        mvc.perform(get("/api/v1/disruption/not-configured")
                        .with(jwt().jwt(token -> token.claim("occ_role", "CAPTAIN"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CAPTAIN"))))
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
    void exposesPrometheusMetricsOnlyToAdministrators() throws Exception {
        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus")
                        .with(jwt().jwt(token -> token.claim("occ_role", "CONTROLLER"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/actuator/prometheus")
                        .with(jwt().jwt(token -> token.claim("occ_role", "ADMIN"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("jvm_memory_used_bytes")));
    }

    @Test
    void exposesOnlyPublicFrontendConfigurationWithoutAuthentication() throws Exception {
        mvc.perform(get("/frontend-config"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"projectUrl":"https://example.supabase.co",
                         "publishableKey":"sb_publishable_synthetic-test"}
                        """));
    }

    @Test
    void backendDoesNotHostAuthTestPage() throws Exception {
        mvc.perform(get("/auth-test/index.html").with(jwt()
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @CsvSource({"CONTROLLER,controller,200", "CONTROLLER,captain,403",
            "CAPTAIN,captain,200", "CAPTAIN,controller,403", "ADMIN,controller,403",
            "ADMIN,captain,403", "UNASSIGNED,controller,403", "UNASSIGNED,captain,403",
            "CAPTAIN,unknown,404", "DRIVER,captain,403", "DRIVER,controller,403"})
    void enforcesExclusivePageAccess(String role, String page, int expected) throws Exception {
        mvc.perform(get("/api/v1/auth/access/" + page)
                        .with(jwt().jwt(token -> token.subject("synthetic-user").claim("occ_role", role))
                                .authorities(new SimpleGrantedAuthority("ROLE_" + role))))
                .andExpect(status().is(expected));
    }

    @ParameterizedTest
    @CsvSource({"CONTROLLER", "CAPTAIN", "ADMIN", "UNASSIGNED"})
    void readsVerifiedIdentityLocallyWithoutCallingIngestion(String role) throws Exception {
        mvc.perform(get("/api/v1/auth/session")
                        .with(jwt().jwt(token -> token.subject("synthetic-user"))
                                .authorities(new SimpleGrantedAuthority("ROLE_" + role))))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"subject\":\"synthetic-user\",\"role\":\"" + role + "\"}"));
    }

    @Test
    void normalizesMissingAuthoritiesToUnassigned() throws Exception {
        mvc.perform(get("/api/v1/auth/session")
                        .with(jwt().jwt(token -> token.subject("synthetic-user")).authorities(java.util.List.of())))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"subject\":\"synthetic-user\",\"role\":\"UNASSIGNED\"}"));
    }

    @Test
    void authEndpointsRequireBearerTokens() throws Exception {
        for (String path : java.util.List.of("/api/v1/auth/session", "/api/v1/auth/access/controller", "/api/v1/auth/access/captain")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization", "Bearer invalid"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "occ.exportOpenapi", matches = "true")
    void exportsGeneratedGatewayContract() throws Exception {
        String contract = mvc.perform(get("/v3/api-docs").with(jwt()
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Path output = Path.of("openapi", "gateway.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, contract + System.lineSeparator());
    }
}
