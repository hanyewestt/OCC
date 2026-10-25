package com.csd.occ.planning.recommendation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.csd.occ.planning.recommendation.domain.RecommendationRecordPort;
import com.csd.occ.planning.recommendation.domain.RecommendationDecisionService;
import com.csd.occ.planning.recommendation.domain.RecommendationDecision;
import com.csd.occ.planning.recommendation.domain.RecommendationDecisionAction;
import com.csd.occ.planning.recommendation.domain.ControllerBusInstruction;
import com.csd.occ.planning.advisory.domain.AdvisoryProposalRepository;
import com.csd.occ.planning.advisory.domain.IssuedInstructionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import com.csd.occ.planning.infrastructure.security.PlanningSecurityConfig;
import com.csd.occ.planning.infrastructure.security.SupabaseRoleConverter;

@WebMvcTest(value = {BunchingRecommendationController.class, RecommendationDecisionController.class,
        com.csd.occ.planning.diagnosis.api.DiagnosisController.class}, properties = {
        "occ.security.supabase.project-url=https://example.supabase.co",
        "occ.security.supabase.jwks-url=https://example.supabase.co/auth/v1/.well-known/jwks.json",
        "occ.security.supabase.audience=authenticated",
        "occ.security.supabase.clock-skew=30s",
        "occ.security.supabase.allowed-algorithms=ES256",
        "occ.security.peer.disruption-to-planning-token=test-disruption-peer-token",
        "occ.security.peer.ingestion-to-planning-token=test-ingestion-peer-token"
})
@Import({PlanningSecurityConfig.class, SupabaseRoleConverter.class, BunchingRecommendationControllerTest.MeterConfiguration.class})
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class BunchingRecommendationControllerTest {
    private static final UUID EVENT_ID = UUID.fromString("c5934f15-4a65-4e94-87ea-a5a64a7dd4c1");
    private static final UUID RECOMMENDATION_ID = UUID.fromString("f77b81b5-4389-49f1-b37a-62e340d7ad97");

    private final MockMvcTester mvc;

    @MockitoBean
    private RecommendationRecordPort records;

    @MockitoBean
    private com.csd.occ.planning.diagnosis.domain.PlanningScopePort scopes;

    @MockitoBean private com.csd.occ.planning.diagnosis.domain.PlanningScopeService scopeQueries;
    @MockitoBean private com.csd.occ.planning.diagnosis.domain.DiagnosisQueryService diagnosisQueries;

    @MockitoBean
    private RecommendationDecisionService decisions;

    @MockitoBean private AdvisoryProposalRepository proposals;
    @MockitoBean private IssuedInstructionRepository instructions;

    @TestConfiguration(proxyBeanMethods = false)
    static class MeterConfiguration {
        @Bean
        MeterRegistry meterRegistry() {
            return new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        }
    }

    BunchingRecommendationControllerTest(MockMvcTester mvc) {
        this.mvc = mvc;
    }

    @Test
    void diagnosisListRequiresAControllerAndReturnsTheServerSelectedScope() {
        org.mockito.Mockito.when(scopeQueries.list(0, 50)).thenReturn(
                new com.csd.occ.planning.diagnosis.domain.ScopedDiagnosisPage("LIVE", null, java.util.List.of(), 0, 50, 0, 0));
        assertThat(mvc.get().uri("/api/v1/planning/diagnoses").exchange()).hasStatus(401);
        assertThat(mvc.get().uri("/api/v1/planning/diagnoses").with(user("captain").roles("CAPTAIN")).exchange()).hasStatus(403);
        assertThat(mvc.get().uri("/api/v1/planning/diagnoses").with(user("controller").roles("CONTROLLER")).exchange())
                .hasStatusOk().bodyJson().extractingPath("$.mode").isEqualTo("LIVE");
    }

    @Test
    void liveSelectionDoesNotReturnSimulationRecommendations() {
        org.mockito.Mockito.when(scopes.current()).thenReturn(new com.csd.occ.planning.diagnosis.domain.PlanningScope("LIVE", null));
        assertThat(mvc.get().uri("/api/v1/planning/recommendations/pending").with(user("controller").roles("CONTROLLER")).exchange())
                .hasStatusOk().bodyJson().extractingPath("$.totalItems").isEqualTo(0);
        org.mockito.Mockito.verify(records, org.mockito.Mockito.never()).findPendingForRun(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void rejectsUnauthenticatedRequests() {
        assertThat(mvc.get()
                .uri("/api/v1/planning/recommendations/by-disruption-event/{id}", EVENT_ID)
                .exchange()).hasStatus(401);
    }

    @Test
    void rejectsRolesThatCannotUseThePlanningApi() {
        assertThat(mvc.get()
                .uri("/api/v1/planning/recommendations/by-disruption-event/{id}", EVENT_ID)
                .with(user("captain").roles("CAPTAIN"))
                .exchange()).hasStatus(403);
    }

    @Test
    void looksUpRecommendationByTheCanonicalPlanningPath() {
        var response = mvc.get()
                .uri("/api/v1/planning/recommendations/by-disruption-event/{id}", EVENT_ID)
                .with(user("controller").roles("CONTROLLER"))
                .exchange();

        assertThat(response).hasStatus(404);
        verify(records).findByBunchingEventId(EVENT_ID);
    }

    @Test
    void recordsOneClickDecisionUsingTheAuthenticatedSubject() {
        Instant decidedAt = Instant.parse("2026-10-21T10:00:00Z");
        org.mockito.Mockito.when(decisions.decide(RECOMMENDATION_ID,
                RecommendationDecisionAction.APPROVED, "controller-subject", java.util.List.of(), null))
                .thenReturn(new RecommendationDecision(RECOMMENDATION_ID,
                        RecommendationDecisionAction.APPROVED, "controller-subject",
                        java.util.List.of(), null, decidedAt));

        var response = mvc.post()
                .uri("/api/v1/planning/recommendations/{id}/decision", RECOMMENDATION_ID)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"action\":\"APPROVED\",\"overrideInstructions\":[]}")
                .with(jwt().jwt(token -> token.subject("controller-subject"))
                        .authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER")))
                .exchange();

        assertThat(response).hasStatus(200)
                .bodyJson().extractingPath("action").isEqualTo("APPROVED");
        verify(decisions).decide(RECOMMENDATION_ID, RecommendationDecisionAction.APPROVED,
                "controller-subject", java.util.List.of(), null);
    }

    @Test
    void recordsControllerReplacementInstructionsAndRationale() {
        Instant decidedAt = Instant.parse("2026-10-21T10:00:00Z");
        var instructions = java.util.List.of(
                new ControllerBusInstruction("SBS5898U", "Proceed without holding."),
                new ControllerBusInstruction("SBS6123A", "Hold 120 seconds at stop 44241."));
        org.mockito.Mockito.when(decisions.decide(RECOMMENDATION_ID,
                RecommendationDecisionAction.REJECTED, "controller-subject", instructions,
                "Widen the gap across both buses."))
                .thenReturn(new RecommendationDecision(RECOMMENDATION_ID,
                        RecommendationDecisionAction.REJECTED, "controller-subject",
                        instructions, "Widen the gap across both buses.", decidedAt));

        var response = mvc.post()
                .uri("/api/v1/planning/recommendations/{id}/decision", RECOMMENDATION_ID)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("""
                        {"action":"REJECTED","overrideInstructions":[
                          {"busReference":"SBS5898U","instruction":"Proceed without holding."},
                          {"busReference":"SBS6123A","instruction":"Hold 120 seconds at stop 44241."}
                        ],"controllerRationale":"Widen the gap across both buses."}
                        """)
                .with(jwt().jwt(token -> token.subject("controller-subject"))
                        .authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER")))
                .exchange();

        assertThat(response).hasStatus(200)
                .bodyJson().extractingPath("overrideInstructions[1].busReference").isEqualTo("SBS6123A");
        verify(decisions).decide(RECOMMENDATION_ID, RecommendationDecisionAction.REJECTED,
                "controller-subject", instructions, "Widen the gap across both buses.");
    }

    @Test
    void rejectsReplacementWithoutControllerRationale() {
        var response = mvc.post()
                .uri("/api/v1/planning/recommendations/{id}/decision", RECOMMENDATION_ID)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("""
                        {"action":"REJECTED","overrideInstructions":[
                          {"busReference":"SBS5898U","instruction":"Proceed without holding."}
                        ]}
                        """)
                .with(jwt().jwt(token -> token.subject("controller-subject"))
                        .authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER")))
                .exchange();

        assertThat(response).hasStatus(400);
    }

    @Test
    void restoresTheSavedDecisionWhenThePageReloads() {
        Instant decidedAt = Instant.parse("2026-10-21T10:00:00Z");
        org.mockito.Mockito.when(decisions.findDecision(RECOMMENDATION_ID)).thenReturn(Optional.of(
                new RecommendationDecision(RECOMMENDATION_ID, RecommendationDecisionAction.REJECTED,
                        "controller-subject", java.util.List.of(), null, decidedAt)));

        var response = mvc.get()
                .uri("/api/v1/planning/recommendations/{id}/decision", RECOMMENDATION_ID)
                .with(user("controller").roles("CONTROLLER"))
                .exchange();

        assertThat(response).hasStatus(200)
                .bodyJson().extractingPath("action").isEqualTo("REJECTED");
    }
}
