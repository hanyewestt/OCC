package com.csd.occ.planning.domain.audit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApplicationAuditQueryService {
    private static final Duration DEFAULT_RANGE = Duration.ofHours(24);
    private final ApplicationAuditEventRepository events;
    private final ApplicationAuditRetentionService retentionService;
    private final Duration retention;
    private final Clock clock;

    public ApplicationAuditQueryService(ApplicationAuditEventRepository events,
            ApplicationAuditRetentionService retentionService, AuditRetentionPolicy properties, Clock clock) {
        this.events = events;
        this.retentionService = retentionService;
        this.retention = properties.retention();
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<ApplicationAuditEvent> search(String category, String eventType, String outcome, String actorSubject,
            UUID runId, String entityId, Instant from, Instant to, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid page or size");
        if (from != null && to != null && from.isAfter(to)) throw new IllegalArgumentException("Invalid time range");
        Instant now = clock.instant();
        Instant cutoff = now.minus(retention);
        Instant start = from == null ? now.minus(DEFAULT_RANGE) : from;
        if (start.isBefore(cutoff)) start = cutoff;
        Instant end = to == null ? now : to;
        if (start.isAfter(end)) throw new IllegalArgumentException("Invalid time range");
        return events.findForAdmin(start, end, likePattern(category), likePattern(eventType), likePattern(outcome),
                likePattern(actorSubject), runId, likePattern(entityId),
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id"))));
    }

    private static String likePattern(String value) {
        if (value == null || value.isBlank()) return null;
        String term = value.trim().toLowerCase(Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + term + "%";
    }

    public long retentionSeconds() { return retention.toSeconds(); }

    public Instant collectionStartedAt() { return retentionService.collectionStartedAt(); }

    @Transactional
    public ApplicationAuditEvent find(UUID id, String actorSubject) {
        Instant cutoff = clock.instant().minus(retention);
        var event = events.findById(id).filter(found -> !found.occurredAt().isBefore(cutoff))
                .orElseThrow(ApplicationAuditNotFoundException::new);
        if ("AI_PROCESSING".equals(event.category())) recordEvidenceAccess(event, actorSubject);
        return event;
    }

    private void recordEvidenceAccess(ApplicationAuditEvent target, String actorSubject) {
        Instant now = clock.instant();
        UUID accessId = UUID.randomUUID();
        String sourceKey = "ai-evidence-access:" + accessId;
        events.save(new ApplicationAuditEvent(accessId, now, now, "OPERATIONS", "AI_EVIDENCE_ACCESSED", "SUCCESS",
                actorSubject, "ADMIN", "APPLICATION_AUDIT_EVENT", target.id().toString(), null, null, null,
                null, "AI processing evidence viewed", Map.of("targetEventId", target.id().toString()), sourceKey));
    }
}
