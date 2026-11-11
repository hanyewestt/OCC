package com.csd.occ.disruption.domain.audit;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApplicationAuditFailureRecorder {
    private final ApplicationAuditEventRepository events;
    private final Clock clock;

    public ApplicationAuditFailureRecorder(ApplicationAuditEventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processingFailure(String component, String scope, UUID correlationId) {
        String safeComponent = switch (component) {
            case "HAZARD_POLL", "HAZARD_REASSESSMENT", "TRAFFIC_INCIDENT_POLL", "PLANNING_SUBMISSION" -> component;
            default -> "PROCESSING";
        };
        String safeScope = switch (scope == null ? "" : scope) {
            case "FLOOD", "RAIL_DISRUPTION", "ROADWORKS", "TRAFFIC_INCIDENT", "BUNCHING" -> scope;
            default -> "UNKNOWN";
        };
        var now = clock.instant();
        var bucket = now.truncatedTo(ChronoUnit.MINUTES);
        String uniqueKey = correlationId == null ? bucket.toString() : correlationId.toString();
        String sourceKey = "processing-failure:" + safeComponent + ":" + safeScope + ":" + uniqueKey;
        events.save(new ApplicationAuditEvent(now, now, "OPERATIONS", "PROCESSING_FAILURE", "FAILURE",
                null, "SYSTEM", safeComponent, safeScope, null,
                "A " + safeComponent.toLowerCase(java.util.Locale.ROOT) + " processing attempt failed",
                Map.of("component", safeComponent, "scope", safeScope), sourceKey));
    }
}
