package com.csd.occ.planning.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.csd.occ.planning.domain.audit.ApplicationAuditEvent;
import com.csd.occ.planning.domain.audit.ApplicationAuditEventRepository;
import jakarta.servlet.DispatcherType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class ApplicationAuthorizationFailureCaptureTest {
    @Test
    void recordsOnlyBoundedRouteAndMethodForDeniedRequest() {
        var repository = mock(ApplicationAuditEventRepository.class);
        var capture = new ApplicationAuthorizationFailureCapture(repository,
                Clock.fixed(Instant.parse("2026-10-25T04:00:00Z"), ZoneOffset.UTC));
        String subject = UUID.randomUUID().toString();
        var jwt = Jwt.withTokenValue("secret-token-value").header("alg", "none")
                .subject(subject).claim("occ_role", "CONTROLLER").build();
        var authentication = new JwtAuthenticationToken(jwt);
        var request = new MockHttpServletRequest("POST", "/api/v1/planning/admin/users/secret-id");
        request.setQueryString("token=query-secret");

        capture.record(request, 403, authentication);

        var event = org.mockito.ArgumentCaptor.forClass(ApplicationAuditEvent.class);
        verify(repository).save(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("AUTHORIZATION_DENIED");
        assertThat(event.getValue().category()).isEqualTo("OPERATIONS");
        assertThat(event.getValue().actorSubject()).isEqualTo(subject);
        assertThat(event.getValue().details()).containsEntry("method", "POST")
                .containsEntry("route", "/api/v1/planning/admin/*");
        assertThat(event.getValue().details().toString()).doesNotContain("secret");
        assertThat(event.getValue().sourceKey()).startsWith("planning-auth-denial:");
    }

    @Test
    void ignoresInternalErrorDispatchesWhenCapturingAuthorizationFailures() {
        var repository = mock(ApplicationAuditEventRepository.class);
        var capture = new ApplicationAuthorizationFailureCapture(repository,
                Clock.fixed(Instant.parse("2026-10-25T04:00:00Z"), ZoneOffset.UTC));
        var request = new MockHttpServletRequest("GET", "/error");
        request.setDispatcherType(DispatcherType.ERROR);

        capture.record(request, 403, null);

        org.mockito.Mockito.verifyNoInteractions(repository);
    }
}
