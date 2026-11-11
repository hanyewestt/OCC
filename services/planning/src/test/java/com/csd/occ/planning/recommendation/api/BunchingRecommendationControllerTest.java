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
import com.csd.occ.planning.advisory.domain.BunchingInstructionService;
import com.csd.occ.planning.advisory.domain.PlanningActor;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.List;
import java.util.Map;
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
        BunchingRecommendationRefreshController.class,
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
    @MockitoBean private BunchingInstructionService bunchingInstructions;
    @MockitoBean private com.csd.occ.planning.recommendation.domain.BunchingRecommendationRefreshService refreshes;

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
    void refreshRequiresAController() {
        String path = "/api/v1/planning/recommendations/" + RECOMMENDATION_ID + "/refresh";
        org.mockito.Mockito.when(refreshes.refresh(RECOMMENDATION_ID))
                .thenThrow(new RecommendationDecisionService.RecommendationNotFoundException(RECOMMENDATION_ID));
        assertThat(mvc.post().uri(path).exchange()).hasStatus(401);
        assertThat(mvc.post().uri(path).with(user("captain").roles("CAPTAIN")).exchange()).hasStatus(403);
        assertThat(mvc.post().uri(path).with(user("admin").roles("ADMIN")).exchange()).hasStatus(403);
        assertThat(mvc.post().uri(path).with(user("controller").roles("CONTROLLER")).exchange()).hasStatus(404);
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
    void pendingCardsUseOneBatchOfRetainedDiagnosisSummaries() {
        UUID runId = UUID.randomUUID();
        UUID diagnosisId = UUID.randomUUID();
        UUID recommendationId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-25T07:05:31Z");
        var record = org.mockito.Mockito.mock(com.csd.occ.planning.recommendation.domain.RecommendationRecord.class);
        var diagnosis = org.mockito.Mockito.mock(com.csd.occ.planning.recommendation.domain.BunchingRootCauseDiagnosis.class);
        var event = org.mockito.Mockito.mock(com.csd.occ.planning.recommendation.domain.ConfirmedBunchingEvent.class);
        var context = org.mockito.Mockito.mock(com.csd.occ.planning.recommendation.domain.ValidatedMitigationContext.class);
        var recommendation = org.mockito.Mockito.mock(com.csd.occ.planning.recommendation.domain.ValidatedMitigationRecommendation.class);
        var explanation = org.mockito.Mockito.mock(com.csd.occ.planning.recommendation.domain.RecommendationExplanation.class);
        var impact = org.mockito.Mockito.mock(com.csd.occ.planning.recommendation.domain.SimulatedImpactEstimate.class);
        org.mockito.Mockito.when(scopes.current()).thenReturn(new com.csd.occ.planning.diagnosis.domain.PlanningScope("DEMO", runId));
        org.mockito.Mockito.when(records.findPendingForRun(runId, 0, 50)).thenReturn(List.of(record));
        org.mockito.Mockito.when(records.countPendingForRun(runId)).thenReturn(1L);
        org.mockito.Mockito.when(record.diagnosis()).thenReturn(diagnosis);
        org.mockito.Mockito.when(record.bunchingEvent()).thenReturn(event);
        org.mockito.Mockito.when(record.classification()).thenReturn(com.csd.occ.planning.recommendation.domain.SourceClassification.SIMULATION);
        org.mockito.Mockito.when(record.mitigationContext()).thenReturn(context);
        org.mockito.Mockito.when(record.recommendation()).thenReturn(recommendation);
        org.mockito.Mockito.when(record.createdAt()).thenReturn(now);
        org.mockito.Mockito.when(diagnosis.diagnosisId()).thenReturn(diagnosisId);
        org.mockito.Mockito.when(event.serviceId()).thenReturn("190");
        org.mockito.Mockito.when(event.directionId()).thenReturn(1);
        org.mockito.Mockito.when(event.monitoredStopCode()).thenReturn("44251");
        org.mockito.Mockito.when(event.firstConfirmedAt()).thenReturn(now);
        org.mockito.Mockito.when(event.expectedHeadwaySeconds()).thenReturn(180);
        org.mockito.Mockito.when(event.observedHeadwaySeconds()).thenReturn(List.of(90, 60));
        org.mockito.Mockito.when(context.affectedBusReferences()).thenReturn(List.of("DEMO-190-B"));
        org.mockito.Mockito.when(recommendation.recommendationId()).thenReturn(recommendationId);
        org.mockito.Mockito.when(recommendation.status()).thenReturn(com.csd.occ.planning.recommendation.domain.RecommendationStatus.RECOMMENDED);
        org.mockito.Mockito.when(recommendation.mitigationType()).thenReturn(com.csd.occ.planning.recommendation.domain.MitigationType.HOLD);
        org.mockito.Mockito.when(recommendation.targetBusReference()).thenReturn("DEMO-190-B");
        org.mockito.Mockito.when(recommendation.controlStopCode()).thenReturn("44251");
        org.mockito.Mockito.when(recommendation.adjustmentSeconds()).thenReturn(120);
        org.mockito.Mockito.when(recommendation.suggestedOutcomes()).thenReturn(List.of(
                new com.csd.occ.planning.recommendation.domain.SuggestedBusOutcome("DEMO-190-B", null, 0, "Manual review.")));
        org.mockito.Mockito.when(recommendation.explanation()).thenReturn(explanation);
        org.mockito.Mockito.when(recommendation.expectedImpact()).thenReturn(impact);
        org.mockito.Mockito.when(explanation.rationale()).thenReturn("Widen the gap.");
        org.mockito.Mockito.when(explanation.likelyCause()).thenReturn("Possible contributing incident: traffic-incident:legacy-uuid.");
        org.mockito.Mockito.when(explanation.tradeOffs()).thenReturn(List.of("Adds waiting time."));
        org.mockito.Mockito.when(explanation.limitations()).thenReturn(List.of("Synthetic data."));
        org.mockito.Mockito.when(impact.headwayBeforeSeconds()).thenReturn(60);
        org.mockito.Mockito.when(impact.headwayAfterSeconds()).thenReturn(180);
        org.mockito.Mockito.when(impact.queueBeforePassengers()).thenReturn(20);
        org.mockito.Mockito.when(impact.queueAfterPassengers()).thenReturn(12);
        org.mockito.Mockito.when(impact.passengerMinutesBefore()).thenReturn(BigDecimal.TEN);
        org.mockito.Mockito.when(impact.passengerMinutesAfter()).thenReturn(BigDecimal.ONE);
        var detail = new com.csd.occ.planning.diagnosis.domain.DiagnosisEvidenceDetail("traffic-incident:source-id",
                "Vehicle Breakdown", "Breakdown near Teck Whye Avenue", now, now, now, 0L, 1807L,
                "bus-arrival:position-id", "44251");
        var summary = new com.csd.occ.planning.diagnosis.domain.DiagnosisSummaryPolicy.Summary(
                "Possible contributor: Vehicle Breakdown. The provider reported: Breakdown near Teck Whye Avenue.",
                List.of(detail));
        org.mockito.Mockito.when(diagnosisQueries.summariesByDiagnosisIds(List.of(diagnosisId)))
                .thenReturn(Map.of(diagnosisId, summary));

        var page = new BunchingRecommendationController(records, scopes, diagnosisQueries).pending(0, 50);

        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.confirmationGaps()).containsExactly(90, 60);
            assertThat(item.suggestedOutcomes()).singleElement().satisfies(outcome -> {
                assertThat(outcome.busReference()).isEqualTo("DEMO-190-B");
                assertThat(outcome.mitigationType()).isNull();
                assertThat(outcome.reason()).isEqualTo("Manual review.");
            });
            assertThat(item.explanation().likelyCause()).contains("Vehicle Breakdown", "Teck Whye Avenue")
                    .doesNotContain("traffic-incident:");
            assertThat(item.explanation().evidenceDetails()).singleElement()
                    .extracting(com.csd.occ.planning.diagnosis.api.dto.DiagnosisEvidenceDetailResponse::distanceMetersFromSuppliedBusPosition)
                    .isEqualTo(1807L);
        });
        org.mockito.Mockito.verify(diagnosisQueries).summariesByDiagnosisIds(List.of(diagnosisId));
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
        org.mockito.Mockito.when(decisions.findDecision(RECOMMENDATION_ID)).thenReturn(Optional.of(
                new RecommendationDecision(RECOMMENDATION_ID, RecommendationDecisionAction.APPROVED,
                        "controller-subject", java.util.List.of(), null, decidedAt)));

        var response = mvc.post()
                .uri("/api/v1/planning/recommendations/{id}/decision", RECOMMENDATION_ID)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"action\":\"APPROVED\"}")
                .with(jwt().jwt(token -> token.subject("controller-subject"))
                        .authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER")))
                .exchange();

        assertThat(response).hasStatus(200)
                .bodyJson().extractingPath("action").isEqualTo("APPROVED");
        verify(bunchingInstructions).approveAndIssue(RECOMMENDATION_ID,
                new PlanningActor("controller-subject", PlanningActor.Role.CONTROLLER));
    }

    @Test
    void dispatchesPerBusControllerReplacementsAndReturnsItsAuditDecision() {
        Instant decidedAt = Instant.parse("2026-10-21T10:00:00Z");
        var auditInstruction = java.util.List.of(new ControllerBusInstruction("DEMO-190-B", "HOLD FOR 60s at next stop"),
                new ControllerBusInstruction("DEMO-190-A", "HOLD FOR 45s at next stop"),
                new ControllerBusInstruction("DEMO-190-C", "HOLD FOR 30s at next stop"),
                new ControllerBusInstruction("DEMO-190-D", "HOLD FOR 20s at next stop"));
        org.mockito.Mockito.when(decisions.findDecision(RECOMMENDATION_ID)).thenReturn(Optional.of(new RecommendationDecision(
                RECOMMENDATION_ID, RecommendationDecisionAction.REJECTED, "controller-subject",
                auditInstruction, "Widen the gap for the target bus.", decidedAt)));

        var response = mvc.post()
                .uri("/api/v1/planning/recommendations/{id}/decision", RECOMMENDATION_ID)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("""
                        {"action":"REJECTED","replacementInstructions":[
                          {"busReference":"DEMO-190-B","action":"HOLD","adjustmentSeconds":60},
                          {"busReference":"DEMO-190-A","action":"HOLD","adjustmentSeconds":45},
                          {"busReference":"DEMO-190-C","action":"HOLD","adjustmentSeconds":30},
                          {"busReference":"DEMO-190-D","action":"HOLD","adjustmentSeconds":20}],
                         "controllerRationale":"Widen the gap for the target bus."}
                        """)
                .with(jwt().jwt(token -> token.subject("controller-subject"))
                        .authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER")))
                .exchange();

        assertThat(response).hasStatus(200)
                .bodyJson().extractingPath("overrideInstructions[1].instruction").isEqualTo("HOLD FOR 45s at next stop");
        verify(bunchingInstructions).rejectAndIssue(RECOMMENDATION_ID,
                new PlanningActor("controller-subject", PlanningActor.Role.CONTROLLER),
                java.util.List.of(new com.csd.occ.planning.advisory.domain.BunchingInstructionService.ReplacementBusInstruction(
                                "DEMO-190-B", com.csd.occ.planning.recommendation.domain.MitigationType.HOLD, 60),
                        new com.csd.occ.planning.advisory.domain.BunchingInstructionService.ReplacementBusInstruction(
                                "DEMO-190-A", com.csd.occ.planning.recommendation.domain.MitigationType.HOLD, 45),
                        new com.csd.occ.planning.advisory.domain.BunchingInstructionService.ReplacementBusInstruction(
                                "DEMO-190-C", com.csd.occ.planning.recommendation.domain.MitigationType.HOLD, 30),
                        new com.csd.occ.planning.advisory.domain.BunchingInstructionService.ReplacementBusInstruction(
                                "DEMO-190-D", com.csd.occ.planning.recommendation.domain.MitigationType.HOLD, 20)),
                "Widen the gap for the target bus.");
    }

    @Test
    void dispatchesReplacementWithoutControllerRationale() {
        Instant decidedAt = Instant.parse("2026-10-21T10:00:00Z");
        var auditInstruction = java.util.List.of(new ControllerBusInstruction("DEMO-190-B", "HOLD FOR 60s at next stop"));
        org.mockito.Mockito.when(decisions.findDecision(RECOMMENDATION_ID)).thenReturn(Optional.of(new RecommendationDecision(
                RECOMMENDATION_ID, RecommendationDecisionAction.REJECTED, "controller-subject",
                auditInstruction, null, decidedAt)));

        var response = mvc.post()
                .uri("/api/v1/planning/recommendations/{id}/decision", RECOMMENDATION_ID)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("""
                        {"action":"REJECTED","replacementInstructions":[
                          {"busReference":"DEMO-190-B","action":"HOLD","adjustmentSeconds":60}]}
                        """)
                .with(jwt().jwt(token -> token.subject("controller-subject"))
                        .authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER")))
                .exchange();

        assertThat(response).hasStatus(200)
                .bodyJson().extractingPath("action").isEqualTo("REJECTED");
        verify(bunchingInstructions).rejectAndIssue(RECOMMENDATION_ID,
                new PlanningActor("controller-subject", PlanningActor.Role.CONTROLLER),
                java.util.List.of(new BunchingInstructionService.ReplacementBusInstruction(
                        "DEMO-190-B", com.csd.occ.planning.recommendation.domain.MitigationType.HOLD, 60)), null);
    }

    @Test
    void dispatchesNoInstructionOutcomeWithItsReason() {
        var audit = List.of(new ControllerBusInstruction("DEMO-190-B", "NO INSTRUCTION: Already spaced"));
        org.mockito.Mockito.when(decisions.findDecision(RECOMMENDATION_ID)).thenReturn(Optional.of(new RecommendationDecision(
                RECOMMENDATION_ID, RecommendationDecisionAction.REJECTED, "controller-subject", audit, null,
                Instant.parse("2026-10-21T10:00:00Z"))));
        var response = mvc.post().uri("/api/v1/planning/recommendations/{id}/decision", RECOMMENDATION_ID)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("""
                        {"action":"REJECTED","replacementInstructions":[{"busReference":"DEMO-190-B",
                         "action":"NO_INSTRUCTION","noInstructionReason":"Already spaced"}]}
                        """)
                .with(jwt().jwt(token -> token.subject("controller-subject"))
                        .authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER"))).exchange();
        assertThat(response).hasStatus(200);
        verify(bunchingInstructions).rejectAndIssue(RECOMMENDATION_ID, new PlanningActor("controller-subject", PlanningActor.Role.CONTROLLER),
                List.of(new BunchingInstructionService.ReplacementBusInstruction("DEMO-190-B", null, 0, "Already spaced")), null);
    }

    @Test
    void rejectsNoInstructionWithoutReason() {
        var response = mvc.post().uri("/api/v1/planning/recommendations/{id}/decision", RECOMMENDATION_ID)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("""
                        {"action":"REJECTED","replacementInstructions":[{"busReference":"DEMO-190-B","action":"NO_INSTRUCTION"}]}
                        """)
                .with(jwt().jwt(token -> token.subject("controller-subject"))
                        .authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER"))).exchange();
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
