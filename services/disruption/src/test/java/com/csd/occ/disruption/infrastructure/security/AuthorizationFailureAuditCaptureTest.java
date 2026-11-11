package com.csd.occ.disruption.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

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
}
