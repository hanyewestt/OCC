package com.csd.occ.planning.api;

import com.csd.occ.planning.infrastructure.security.PlanningSecurityConfig;
import com.csd.occ.planning.infrastructure.security.SupabaseRoleConverter;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(value = AuthorizationBoundaryTest.Probe.class, properties = {
        "occ.security.supabase.project-url=https://issuer.test",
        "occ.security.supabase.jwks-url=https://issuer.test/jwks",
        "occ.security.supabase.audience=authenticated",
        "occ.security.supabase.clock-skew=30s",
        "occ.security.supabase.allowed-algorithms=ES256",
        "occ.security.peer.disruption-to-planning-token=synthetic-disruption-to-planning",
        "occ.security.peer.ingestion-to-planning-token=synthetic-ingestion-to-planning"})
@Import({PlanningSecurityConfig.class, SupabaseRoleConverter.class, AuthorizationBoundaryTest.Probe.class})
class AuthorizationBoundaryTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean com.csd.occ.planning.advisory.domain.AdvisoryProposalRepository proposals;
    @MockitoBean com.csd.occ.planning.advisory.domain.IssuedInstructionRepository instructions;

    @ParameterizedTest
    @CsvSource({"GET,ADMIN,200", "GET,CONTROLLER,200", "GET,CAPTAIN,403", "GET,UNASSIGNED,403",
            "POST,ADMIN,403", "PUT,ADMIN,403", "PATCH,ADMIN,403", "DELETE,ADMIN,403",
            "POST,CONTROLLER,200", "PUT,CONTROLLER,403", "PATCH,CONTROLLER,403", "DELETE,CONTROLLER,403",
            "POST,CAPTAIN,403", "POST,UNASSIGNED,403", "HEAD,CONTROLLER,403"})
    void operationalReadsAndWritesHaveSeparateRoles(String method, String role, int expected) throws Exception {
        perform(method, "/api/v1/planning/notices", role, expected);
    }

    @ParameterizedTest
    @CsvSource({"GET,ADMIN,200", "POST,ADMIN,200", "PUT,ADMIN,200", "PATCH,ADMIN,403", "DELETE,ADMIN,403", "HEAD,ADMIN,403",
            "GET,CONTROLLER,403", "POST,CONTROLLER,403", "PUT,CONTROLLER,403", "PATCH,CONTROLLER,403", "DELETE,CONTROLLER,403",
            "GET,CAPTAIN,403", "POST,CAPTAIN,403", "GET,UNASSIGNED,403"})
    void adminNamespaceCannotFallThroughToOperationalRules(String method, String role, int expected) throws Exception {
        perform(method, "/api/v1/planning/admin/probe", role, expected);
    }

    @ParameterizedTest
    @CsvSource({"GET,ADMIN", "POST,ADMIN", "GET,CONTROLLER", "POST,CONTROLLER", "GET,CAPTAIN"})
    void unknownPathsDefaultDeny(String method, String role) throws Exception {
        perform(method, "/api/v1/unknown", role, 403);
        perform(method, "/api/v1/planning/unknown", role, 403);
        perform(method, "/api/v1/planning/notices/unknown/unsupported", role, 403);
        perform(method, "/unknown", role, 403);
    }

    private void perform(String method, String path, String role, int expected) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), path)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role))))
                .andExpect(status().is(expected));
    }

    @RestController
    static class Probe {
        @RequestMapping({"/api/v1/planning/notices", "/api/v1/planning/admin/probe"})
        String respond() { return "allowed"; }
    }
}
