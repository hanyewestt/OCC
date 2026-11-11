package com.csd.occ.disruption.infrastructure.security;

import com.csd.occ.disruption.domain.audit.ApplicationAuditEvent;
import com.csd.occ.disruption.domain.audit.ApplicationAuditEventRepository;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthorizationFailureAuditCapture {
    private final ApplicationAuditEventRepository events;
    private final Clock clock;

    public AuthorizationFailureAuditCapture(ApplicationAuditEventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(HttpServletRequest request, String reason, Authentication authentication) {
        if (request.getDispatcherType() == DispatcherType.ERROR) return;
        var now = clock.instant();
        var bucket = now.truncatedTo(ChronoUnit.MINUTES);
        var route = routeLabel(request.getMethod(), request.getRequestURI());
        boolean verified = authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
        String actor = verified ? authentication.getName() : "anonymous";
        String actorType = verified ? authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .map(AuthorizationFailureAuditCapture::roleType).filter(java.util.Objects::nonNull).findFirst()
                .orElse("AUTHENTICATED") : "ANONYMOUS";
        String boundedReason = "ROLE_NOT_PERMITTED".equals(reason) ? reason : "AUTHENTICATION_REQUIRED";
        String sourceKey = "authorization-denied:" + UUID.nameUUIDFromBytes(
                (route + ":" + boundedReason + ":" + actor + ":" + bucket).getBytes(StandardCharsets.UTF_8));
        events.save(new ApplicationAuditEvent(now, now, "OPERATIONS", "AUTHORIZATION_DENIED", "DENIED",
                verified ? actor : null, actorType, "ROUTE", route, null,
                "Access denied for " + route + " (" + boundedReason + ")",
                Map.of("route", route, "reason", boundedReason), sourceKey));
    }

    static String routeLabel(String method, String path) {
        if (path == null) return "DISRUPTION_API";
        if (path.equals("/api/v1/disruption/admin/audit-events")) return method.equals("GET") ? "ADMIN_AUDIT_LIST" : "ADMIN_AUDIT_MUTATION";
        if (path.matches("/api/v1/disruption/admin/audit-events/[^/]+")) return method.equals("GET") ? "ADMIN_AUDIT_DETAIL" : "ADMIN_AUDIT_MUTATION";
        if (path.equals("/api/v1/disruption/admin") || path.startsWith("/api/v1/disruption/admin/")) return "ADMIN_API";
        if (path.equals("/api/v1/disruption/traffic-incidents")) return method.equals("GET") ? "TRAFFIC_INCIDENTS_READ" : "TRAFFIC_INCIDENTS_WRITE";
        if (path.equals("/api/v1/disruption/bunching-alerts")) return method.equals("GET") ? "BUNCHING_ALERTS_READ" : "BUNCHING_ALERTS_WRITE";
        if (path.equals("/api/v1/disruption/hazards") || path.matches("/api/v1/disruption/hazards/[^/]+"))
            return method.equals("GET") ? "HAZARDS_READ" : "HAZARDS_WRITE";
        return "DISRUPTION_API";
    }

    private static String roleType(String authority) {
        return switch (authority) {
            case "ROLE_ADMIN" -> "ADMIN";
            case "ROLE_CONTROLLER" -> "CONTROLLER";
            case "ROLE_CAPTAIN" -> "CAPTAIN";
            default -> null;
        };
    }
}
