package com.csd.occ.disruption.api;

import com.csd.occ.disruption.infrastructure.security.DisruptionSecurityConfig;
import com.csd.occ.disruption.infrastructure.security.SupabaseRoleConverter;
import com.csd.occ.disruption.infrastructure.security.AuditAccessDeniedHandler;
import com.csd.occ.disruption.infrastructure.security.AuditAuthenticationEntryPoint;
import com.csd.occ.disruption.infrastructure.security.AuthorizationFailureAuditCapture;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

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
        "occ.security.peer.ingestion-to-disruption-token=synthetic-ingestion-to-disruption",
        "occ.security.peer.planning-to-disruption-token=synthetic-planning-to-disruption"})
@Import({DisruptionSecurityConfig.class, SupabaseRoleConverter.class, AuditAuthenticationEntryPoint.class,
        AuditAccessDeniedHandler.class, AuthorizationBoundaryTest.Probe.class})
class AuthorizationBoundaryTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean AuthorizationFailureAuditCapture audit;

    @ParameterizedTest
    @CsvSource({"GET,ADMIN,200", "GET,CONTROLLER,200", "GET,CAPTAIN,403", "GET,UNASSIGNED,403",
            "POST,ADMIN,403", "PUT,ADMIN,403", "PATCH,ADMIN,403", "DELETE,ADMIN,403",
            "POST,CONTROLLER,403", "PUT,CONTROLLER,403", "PATCH,CONTROLLER,403", "DELETE,CONTROLLER,403",
            "POST,CAPTAIN,403", "POST,UNASSIGNED,403", "HEAD,CONTROLLER,403"})
    void operationalReadsAndWritesHaveSeparateRoles(String method, String role, int expected) throws Exception {
        perform(method, "/api/v1/disruption/traffic-incidents", role, expected);
    }

    @ParameterizedTest
    @CsvSource({"GET,ADMIN,200", "POST,ADMIN,200", "PUT,ADMIN,200", "PATCH,ADMIN,403", "DELETE,ADMIN,403", "HEAD,ADMIN,403",
            "GET,CONTROLLER,403", "POST,CONTROLLER,403", "PUT,CONTROLLER,403", "PATCH,CONTROLLER,403", "DELETE,CONTROLLER,403",
            "GET,CAPTAIN,403", "POST,CAPTAIN,403", "GET,UNASSIGNED,403"})
    void adminNamespaceCannotFallThroughToOperationalRules(String method, String role, int expected) throws Exception {
        perform(method, "/api/v1/disruption/admin/probe", role, expected);
    }

    @ParameterizedTest
    @CsvSource({"ADMIN,200", "CONTROLLER,403", "CAPTAIN,403", "UNASSIGNED,403"})
    void auditListAndDetailAreAdminOnly(String role, int expected) throws Exception {
        perform("GET", "/api/v1/disruption/admin/audit-events", role, expected);
        perform("GET", "/api/v1/disruption/admin/audit-events/00000000-0000-0000-0000-000000000001", role, expected);
    }

    @ParameterizedTest
    @CsvSource({"GET,ADMIN", "POST,ADMIN", "GET,CONTROLLER", "POST,CONTROLLER", "GET,CAPTAIN"})
    void unknownPathsDefaultDeny(String method, String role) throws Exception {
        perform(method, "/api/v1/unknown", role, 403);
        perform(method, "/api/v1/disruption/unknown", role, 403);
        perform(method, "/api/v1/disruption/traffic-incidents/unknown", role, 403);
        perform(method, "/unknown", role, 403);
    }

    @org.junit.jupiter.api.Test
    void anonymousAdminRequestRetainsBearerAuthenticationBehavior() throws Exception {
        mvc.perform(request(HttpMethod.GET, "/api/v1/disruption/admin/probe"))
                .andExpect(status().isUnauthorized());
        verify(audit).record(any(), eq("AUTHENTICATION_REQUIRED"), any());
    }

    private void perform(String method, String path, String role, int expected) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), path)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role))))
                .andExpect(status().is(expected));
        if (expected == 403) verify(audit, atLeastOnce()).record(any(), eq("ROLE_NOT_PERMITTED"), any());
    }

    @RestController
    static class Probe {
        @RequestMapping({"/api/v1/disruption/traffic-incidents", "/api/v1/disruption/admin/probe",
                "/api/v1/disruption/admin/audit-events", "/api/v1/disruption/admin/audit-events/{id}"})
        String respond() { return "allowed"; }
    }
}
