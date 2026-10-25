package com.csd.occ.planning.diagnosis.api;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.csd.occ.planning.advisory.domain.AdvisoryProposalRepository;
import com.csd.occ.planning.advisory.domain.IssuedInstructionRepository;
import com.csd.occ.planning.api.error.GlobalExceptionHandler;
import com.csd.occ.planning.diagnosis.domain.DiagnosisQueryService;
import com.csd.occ.planning.diagnosis.domain.DiagnosisRequestStatus;
import com.csd.occ.planning.diagnosis.domain.PlanningHazardRequestSummary;
import com.csd.occ.planning.diagnosis.domain.PlanningScopeService;
import com.csd.occ.planning.diagnosis.domain.ScopedHazardRequestPage;
import com.csd.occ.planning.infrastructure.security.PlanningSecurityConfig;
import com.csd.occ.planning.infrastructure.security.SupabaseRoleConverter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value = DiagnosisController.class, properties = {
        "occ.security.supabase.project-url=https://example.supabase.co",
        "occ.security.supabase.jwks-url=https://example.supabase.co/auth/v1/.well-known/jwks.json",
        "occ.security.supabase.audience=authenticated",
        "occ.security.supabase.clock-skew=30s",
        "occ.security.supabase.allowed-algorithms=ES256",
        "occ.security.peer.disruption-to-planning-token=test-peer-token",
        "occ.security.peer.ingestion-to-planning-token=test-ingestion-peer-token"
})
@Import({GlobalExceptionHandler.class, PlanningSecurityConfig.class, SupabaseRoleConverter.class})
class DiagnosisControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean DiagnosisQueryService diagnoses;
    @MockitoBean PlanningScopeService scopes;
    @MockitoBean AdvisoryProposalRepository proposals;
    @MockitoBean IssuedInstructionRepository instructions;

    @Test
    void exposesOnlyScopedUnfinishedHazardStatusesToControllers() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID disruptionId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        when(scopes.listHazardRequests(0, 10)).thenReturn(new ScopedHazardRequestPage("DEMO", runId,
                List.of(new PlanningHazardRequestSummary(requestId, eventId, disruptionId, "HAZARD",
                        DiagnosisRequestStatus.FAILED_RETRYABLE, "HAZARD_CONTEXT_OR_MODEL_UNAVAILABLE",
                        Instant.parse("2026-10-25T04:00:00Z"))), 0, 10, 1, 1));

        mvc.perform(get("/api/v1/planning/diagnoses/hazard-requests?page=0&size=10")
                        .with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CONTROLLER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("DEMO"))
                .andExpect(jsonPath("$.demoRunId").value(runId.toString()))
                .andExpect(jsonPath("$.items[0].eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.items[0].disruptionId").value(disruptionId.toString()))
                .andExpect(jsonPath("$.items[0].status").value("FAILED_RETRYABLE"))
                .andExpect(jsonPath("$.items[0].failureCode").value("HAZARD_CONTEXT_OR_MODEL_UNAVAILABLE"));
    }

    @Test
    void hazardStatusEndpointRequiresAnOperationalRole() throws Exception {
        mvc.perform(get("/api/v1/planning/diagnoses/hazard-requests"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/planning/diagnoses/hazard-requests")
                        .with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CAPTAIN"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(scopes);
    }
}
