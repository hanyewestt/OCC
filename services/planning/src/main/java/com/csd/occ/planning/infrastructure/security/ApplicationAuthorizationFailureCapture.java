package com.csd.occ.planning.infrastructure.security;

import com.csd.occ.planning.domain.audit.ApplicationAuditEvent;
import com.csd.occ.planning.domain.audit.ApplicationAuditEventRepository;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ApplicationAuthorizationFailureCapture {
    private final ApplicationAuditEventRepository events;
    private final Clock clock;

    public ApplicationAuthorizationFailureCapture(ApplicationAuditEventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(HttpServletRequest request, int status, Authentication authentication) {
        if (request.getDispatcherType() == DispatcherType.ERROR) return;
        var now = clock.instant();
        String method = switch (request.getMethod()) {
            case "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS" -> request.getMethod();
            default -> "OTHER";
        };
        String route = route(request.getRequestURI());
        String subject = authentication == null || authentication.getName() == null ? null : authentication.getName();
        if (subject != null) {
            try {
                subject = UUID.fromString(subject).toString();
            } catch (IllegalArgumentException ignored) {
                subject = null;
            }
        }
        String role = authentication == null || authentication.getAuthorities().isEmpty()
                ? "ANONYMOUS" : authentication.getAuthorities().iterator().next().getAuthority();
        if (role == null || role.length() > 40 || !role.matches("ROLE_(ADMIN|CONTROLLER|CAPTAIN|UNASSIGNED)")) role = "AUTHENTICATED";
        else role = role.substring("ROLE_".length());
        String id = UUID.randomUUID().toString();
        events.save(new ApplicationAuditEvent(UUID.randomUUID(), now, now, "OPERATIONS",
                "AUTHORIZATION_DENIED", Integer.toString(status), subject, role, "HTTP_ROUTE", route,
                null, null, null, null, "Request denied by application authorization", Map.of("method", method, "route", route),
                "planning-auth-denial:" + id));
    }

    private static String route(String path) {
        if (path == null) return "OTHER";
        if (path.startsWith("/api/v1/planning/admin/")) return "/api/v1/planning/admin/*";
        if (path.startsWith("/api/v1/planning/captain/")) return "/api/v1/planning/captain/*";
        if (path.startsWith("/api/v1/planning/")) return "/api/v1/planning/*";
        if (path.startsWith("/internal/v1/planning/")) return "/internal/v1/planning/*";
        if (path.startsWith("/actuator/")) return "/actuator/*";
        return "OTHER";
    }
}
