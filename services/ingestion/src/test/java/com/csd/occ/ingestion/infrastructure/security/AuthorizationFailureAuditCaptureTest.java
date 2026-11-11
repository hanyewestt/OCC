package com.csd.occ.ingestion.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.csd.occ.ingestion.domain.ApplicationAuditEvent;
import com.csd.occ.ingestion.domain.ApplicationAuditEventRepository;
import jakarta.servlet.DispatcherType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class AuthorizationFailureAuditCaptureTest {
    private final ApplicationAuditEventRepository events = mock(ApplicationAuditEventRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-11T01:02:03Z"), ZoneOffset.UTC);
    private final AuthorizationFailureAuditCapture capture = new AuthorizationFailureAuditCapture(events, clock);

    @Test
    void labelsDeniedSimulationLifecycleWritesWithoutExposingRequestValues() {
        assertThat(AuthorizationFailureAuditCapture.routeLabel("POST", "/api/v1/demo-runs"))
                .isEqualTo("SIMULATION_START");
        assertThat(AuthorizationFailureAuditCapture.routeLabel("POST",
                "/api/v1/demo-runs/secret-run-id/close")).isEqualTo("SIMULATION_CLOSE");
    }

    @Test
    void capturesOnlyBoundedRouteAndVerifiedActorForAuthorizationDenial() {
        var request = new MockHttpServletRequest("GET", "/api/v1/admin/users/secret-account-id");
        request.setQueryString("search=private-query");
        var actor = UsernamePasswordAuthenticationToken.authenticated("verified-user", "ignored-credentials",
                List.of(new SimpleGrantedAuthority("ROLE_CONTROLLER")));

        capture.record(request, true, actor);

        var event = org.mockito.ArgumentCaptor.forClass(ApplicationAuditEvent.class);
        verify(events).save(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("AUTHORIZATION_DENIED");
        assertThat(event.getValue().outcome()).isEqualTo("DENIED");
        assertThat(event.getValue().actorSubject()).isEqualTo("verified-user");
        assertThat(event.getValue().actorType()).isEqualTo("CONTROLLER");
        assertThat(event.getValue().entityId()).isEqualTo("ADMIN_USERS_DETAIL");
        assertThat(event.getValue().details()).containsEntry("route", "ADMIN_USERS_DETAIL")
                .containsEntry("reason", "ROLE_NOT_PERMITTED");
        assertThat(event.getValue().sourceKey()).doesNotContain("secret-account-id", "private-query", "ignored-credentials");
    }

    @Test
    void unauthenticatedDenialDoesNotTrustJwtLookingIdentity() {
        var request = new MockHttpServletRequest("GET", "/api/v1/admin/users");
        var unauthenticated = UsernamePasswordAuthenticationToken.unauthenticated("unverified-claim", "token");

        capture.record(request, false, unauthenticated);

        var event = org.mockito.ArgumentCaptor.forClass(ApplicationAuditEvent.class);
        verify(events).save(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("AUTHENTICATION_DENIED");
        assertThat(event.getValue().actorSubject()).isNull();
        assertThat(event.getValue().actorType()).isEqualTo("ANONYMOUS");
        assertThat(event.getValue().entityId()).isEqualTo("ADMIN_USERS_LIST");
        assertThat(event.getValue().summary()).doesNotContain("unverified-claim", "token");
    }

    @Test
    void doesNotRecordInternalErrorDispatchAsUserAuthorizationFailure() {
        var request = new MockHttpServletRequest("POST", "/error");
        request.setDispatcherType(DispatcherType.ERROR);
        var actor = UsernamePasswordAuthenticationToken.authenticated("verified-user", "ignored-credentials",
                List.of(new SimpleGrantedAuthority("ROLE_CONTROLLER")));

        capture.record(request, true, actor);

        verifyNoInteractions(events);
    }
}
