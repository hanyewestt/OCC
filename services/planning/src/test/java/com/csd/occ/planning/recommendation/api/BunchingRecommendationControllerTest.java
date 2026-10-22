package com.csd.occ.planning.recommendation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.csd.occ.planning.recommendation.domain.RecommendationRecordPort;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import com.csd.occ.planning.infrastructure.security.PlanningSecurityConfig;
import com.csd.occ.planning.infrastructure.security.SupabaseRoleConverter;

@WebMvcTest(value = BunchingRecommendationController.class, properties = {
        "occ.security.supabase.project-url=https://example.supabase.co",
        "occ.security.supabase.jwks-url=https://example.supabase.co/auth/v1/.well-known/jwks.json",
        "occ.security.supabase.audience=authenticated",
        "occ.security.supabase.clock-skew=30s",
        "occ.security.supabase.allowed-algorithms=ES256",
        "occ.security.peer.disruption-to-planning-token=test-disruption-peer-token",
        "occ.security.peer.ingestion-to-planning-token=test-ingestion-peer-token"
})
@Import({PlanningSecurityConfig.class, SupabaseRoleConverter.class})
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class BunchingRecommendationControllerTest {
    private static final UUID EVENT_ID = UUID.fromString("c5934f15-4a65-4e94-87ea-a5a64a7dd4c1");

    private final MockMvcTester mvc;

    @MockitoBean
    private RecommendationRecordPort records;

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
}
