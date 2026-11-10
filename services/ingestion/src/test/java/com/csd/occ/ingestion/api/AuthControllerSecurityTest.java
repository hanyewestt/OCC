package com.csd.occ.ingestion.api;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.csd.occ.ingestion.infrastructure.security.IngestionSecurityConfig;
import com.csd.occ.ingestion.infrastructure.security.SupabaseRoleConverter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value = AuthController.class, properties = {
        "occ.security.supabase.project-url=https://issuer.test",
        "occ.security.supabase.jwks-url=https://issuer.test/jwks",
        "occ.security.supabase.audience=authenticated",
        "occ.security.supabase.clock-skew=30s",
        "occ.security.supabase.allowed-algorithms=ES256",
        "occ.security.peer.disruption-to-ingestion-token=disruption-token",
        "occ.security.peer.planning-to-ingestion-token=planning-token"})
@Import({IngestionSecurityConfig.class, SupabaseRoleConverter.class})
class AuthControllerSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;

    @Test
    void sessionRequiresABearerToken() throws Exception {
        mvc.perform(get("/api/v1/auth/session")).andExpect(status().isUnauthorized());
    }

    @Test
    void pageAccessRequiresABearerToken() throws Exception {
        mvc.perform(get("/api/v1/auth/access/controller")).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @CsvSource({"CONTROLLER,controller", "CAPTAIN,captain"})
    void successfulPageAccessIsNotCached(String role, String page) throws Exception {
        mvc.perform(get("/api/v1/auth/access/" + page)
                        .with(jwt().jwt(token -> token.subject("synthetic-user"))
                                .authorities(new SimpleGrantedAuthority("ROLE_" + role))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.subject").value("synthetic-user"))
                .andExpect(jsonPath("$.role").value(role));
    }

    @ParameterizedTest
    @CsvSource({"CONTROLLER", "CAPTAIN", "ADMIN", "UNASSIGNED"})
    void readsVerifiedIdentityForAnyRecognizedRole(String role) throws Exception {
        mvc.perform(get("/api/v1/auth/session")
                        .with(jwt().jwt(token -> token.subject("synthetic-user"))
                                .authorities(new SimpleGrantedAuthority("ROLE_" + role))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.subject").value("synthetic-user"))
                .andExpect(jsonPath("$.role").value(role));
    }

    @Test
    void normalizesMissingAuthoritiesToUnassigned() throws Exception {
        mvc.perform(get("/api/v1/auth/session")
                        .with(jwt().jwt(token -> token.subject("synthetic-user")).authorities(List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("UNASSIGNED"));
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

    @Test
    void roleDenialUsesProblemJson() throws Exception {
        mvc.perform(get("/api/v1/auth/access/controller")
                        .with(jwt().jwt(token -> token.subject("synthetic-user"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(content().string(containsString("This role cannot access this page")));
    }

    @Test
    void unknownPageUsesProblemJson() throws Exception {
        mvc.perform(get("/api/v1/auth/access/unknown")
                        .with(jwt().jwt(token -> token.subject("synthetic-user"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER"))))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(content().string(containsString("Unknown page")));
    }
}
