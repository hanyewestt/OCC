package com.csd.occ.ingestion.infrastructure.security;

import com.csd.occ.ingestion.domain.ApplicationAuditEvent;
import com.csd.occ.ingestion.domain.ApplicationAuditEventRepository;
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

/** Stores a bounded, credential-free record when an application API request is denied. */
@Service
public class AuthorizationFailureAuditCapture {
    private final ApplicationAuditEventRepository events;
    private final Clock clock;

    public AuthorizationFailureAuditCapture(ApplicationAuditEventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(HttpServletRequest request, boolean forbidden, Authentication authentication) {
        var now = clock.instant();
        var route = routeLabel(request.getMethod(), request.getRequestURI());
        boolean verified = authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
        String actor = verified ? bounded(authentication.getName(), 255) : null;
        String actorType = verified ? authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .map(AuthorizationFailureAuditCapture::roleType).filter(java.util.Objects::nonNull).findFirst()
                .orElse("AUTHENTICATED") : "ANONYMOUS";
        String reason = forbidden ? "ROLE_NOT_PERMITTED" : "AUTHENTICATION_REQUIRED";
        String eventType = forbidden ? "AUTHORIZATION_DENIED" : "AUTHENTICATION_DENIED";
        String bucket = now.truncatedTo(ChronoUnit.MINUTES).toString();
        String identity = route + ":" + eventType + ":" + (actor == null ? "anonymous" : actor) + ":" + bucket;
        String sourceKey = "authorization-denied:" + UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
        UUID id = UUID.nameUUIDFromBytes(sourceKey.getBytes(StandardCharsets.UTF_8));
        events.save(new ApplicationAuditEvent(id, now, now, "OPERATIONS", eventType, "DENIED", "INGESTION",
                actor, actorType, "ROUTE", route, null, null, null, null,
                "Access denied for " + route + " (" + reason + ")", Map.of("route", route, "reason", reason), sourceKey));
    }

    static String routeLabel(String method, String path) {
        if (path == null) return "INGESTION_API";
        if (path.equals("/api/v1/admin/users/synchronizations")) return "ADMIN_DIRECTORY_SYNC";
        if (path.equals("/api/v1/admin/users")) {
            return switch (method) {
                case "GET" -> "ADMIN_USERS_LIST";
                case "POST" -> "ADMIN_USER_CREATE";
                default -> "ADMIN_USERS";
            };
        }
        if (path.matches("/api/v1/admin/users/[^/]+")) return "ADMIN_USERS_DETAIL";
        if (path.matches("/api/v1/admin/provisioning-operations/[^/]+")) return "ADMIN_PROVISIONING_OPERATION";
        if (path.equals("/api/v1/admin/audit-events")) return "ADMIN_AUDIT_LIST";
        if (path.matches("/api/v1/admin/audit-events/[^/]+")) return "ADMIN_AUDIT_DETAIL";
        if (path.equals("/api/v1/admin/demo-runs/active/assignments")) return "ADMIN_ASSIGNMENTS_LIST";
        if (path.matches("/api/v1/demo-runs/[^/]+/buses/[^/]+/assignment")) return "ADMIN_ASSIGNMENT_CHANGE";
        if (path.equals("/api/v1/captains")) return "CAPTAINS_LIST";
        if (path.equals("/api/v1/auth/access/admin")) return "ADMIN_ACCESS_CHECK";
        if (path.startsWith("/api/v1/")) return "INGESTION_API";
        return "OTHER_ROUTE";
    }

    private static String roleType(String authority) {
        return switch (authority) {
            case "ROLE_ADMIN" -> "ADMIN";
            case "ROLE_CONTROLLER" -> "CONTROLLER";
            case "ROLE_CAPTAIN" -> "CAPTAIN";
            default -> null;
        };
    }

    private static String bounded(String value, int max) {
        if (value == null || value.isBlank()) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
