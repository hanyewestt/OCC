package com.csd.occ.planning.recommendation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.csd.occ.planning.recommendation.domain.RecommendationRecordPort;
import com.csd.occ.planning.recommendation.domain.RecommendationDecisionService;
import com.csd.occ.planning.recommendation.domain.RecommendationDecision;
import com.csd.occ.planning.recommendation.domain.RecommendationDecisionAction;
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

@WebMvcTest(value = {BunchingRecommendationController.class, RecommendationDecisionController.class}, properties = {
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
    private RecommendationDecisionService decisions;

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
    void rejectsUnauthenticatedRequests() {
        assertThat(mvc.get()
                .uri("/api/v1/planning/recommendations/by-disruption-event/{id}", EVENT_ID)
                .exchange()).hasStatus(401);
    }

    @Test
    void rejectsRolesThatCannotUseThePlanningApi() {
        assertThat(mvc.get()
                .uri("/api/v1/planning/recommendations/by-disruption-event/{id}", EVENT_ID)
                .with(user("driver").roles("DRIVER"))
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
                RecommendationDecisionAction.APPROVED, "controller-subject"))
                .thenReturn(new RecommendationDecision(RECOMMENDATION_ID,
                        RecommendationDecisionAction.APPROVED, "controller-subject", decidedAt));

        var response = mvc.post()
                .uri("/api/v1/planning/recommendations/{id}/decision", RECOMMENDATION_ID)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"action\":\"APPROVED\"}")
                .with(jwt().jwt(token -> token.subject("controller-subject"))
                        .authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER")))
                .exchange();

        assertThat(response).hasStatus(200)
                .bodyJson().extractingPath("action").isEqualTo("APPROVED");
        verify(decisions).decide(RECOMMENDATION_ID, RecommendationDecisionAction.APPROVED, "controller-subject");
    }

    @Test
    void restoresTheSavedDecisionWhenThePageReloads() {
        Instant decidedAt = Instant.parse("2026-10-21T10:00:00Z");
        org.mockito.Mockito.when(decisions.findDecision(RECOMMENDATION_ID)).thenReturn(Optional.of(
                new RecommendationDecision(RECOMMENDATION_ID, RecommendationDecisionAction.REJECTED,
                        "controller-subject", decidedAt)));

        var response = mvc.get()
                .uri("/api/v1/planning/recommendations/{id}/decision", RECOMMENDATION_ID)
                .with(user("controller").roles("CONTROLLER"))
                .exchange();

        assertThat(response).hasStatus(200)
                .bodyJson().extractingPath("action").isEqualTo("REJECTED");
    }
}
