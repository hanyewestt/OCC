package com.csd.occ.disruption.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.csd.occ.disruption.domain.audit.ApplicationAuditEventRepository;
import jakarta.servlet.DispatcherType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class AuthorizationFailureAuditCaptureTest {
    @Test
    void mapsRequestsToBoundedRouteLabelsWithoutPersistingPathIdentifiers() {
        assertThat(AuthorizationFailureAuditCapture.routeLabel("GET", "/api/v1/disruption/admin/audit-events"))
                .isEqualTo("ADMIN_AUDIT_LIST");
        assertThat(AuthorizationFailureAuditCapture.routeLabel("GET", "/api/v1/disruption/admin/audit-events/secret-id"))
                .isEqualTo("ADMIN_AUDIT_DETAIL");
        assertThat(AuthorizationFailureAuditCapture.routeLabel("DELETE", "/api/v1/disruption/admin/audit-events/secret-id"))
                .isEqualTo("ADMIN_AUDIT_MUTATION");
        assertThat(AuthorizationFailureAuditCapture.routeLabel("GET", "/api/v1/disruption/unknown/secret-id"))
                .isEqualTo("DISRUPTION_API");
    }

    @Test
    void ignoresInternalErrorDispatchesWhenCapturingAuthorizationFailures() {
        var events = mock(ApplicationAuditEventRepository.class);
        var capture = new AuthorizationFailureAuditCapture(events,
                Clock.fixed(Instant.parse("2026-11-11T01:02:03Z"), ZoneOffset.UTC));
        var request = new MockHttpServletRequest("GET", "/error");
        request.setDispatcherType(DispatcherType.ERROR);

        capture.record(request, "ROLE_NOT_PERMITTED", null);

        verifyNoInteractions(events);
    }
}
