package com.csd.occ.disruption.domain.audit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApplicationAuditQueryService {
    private final ApplicationAuditEventRepository events;
    private final ApplicationAuditMetadataRepository metadata;
    private final AuditRetentionPolicy retention;
    private final Clock clock;

    public ApplicationAuditQueryService(ApplicationAuditEventRepository events, ApplicationAuditMetadataRepository metadata,
            AuditRetentionPolicy retention, Clock clock) {
        this.events = events;
        this.metadata = metadata;
        this.retention = retention;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<ApplicationAuditEvent> search(String category, String eventType, String outcome, String actorSubject,
            UUID runId, String entityId, Instant from, Instant to, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid audit page");
        Instant now = clock.instant();
        Instant cutoff = now.minus(retention.retention());
        Instant effectiveTo = to == null ? now : to;
        Instant effectiveFrom = from == null ? effectiveTo.minus(Duration.ofHours(24)) : from;
        if (effectiveFrom.isAfter(effectiveTo)) throw new IllegalArgumentException("Audit time range is invalid");
        if (effectiveFrom.isBefore(cutoff)) effectiveFrom = cutoff;
        Instant selectedFrom = effectiveFrom;

        Specification<ApplicationAuditEvent> spec = (root, query, cb) -> cb.and(
                cb.greaterThanOrEqualTo(root.get("occurredAt"), cutoff),
                cb.greaterThanOrEqualTo(root.get("occurredAt"), selectedFrom),
                cb.lessThanOrEqualTo(root.get("occurredAt"), effectiveTo));
        if (category != null && !category.isBlank()) spec = spec.and((root, query, cb) -> cb.like(cb.lower(root.get("category")), likePattern(category), '!'));
        if (eventType != null && !eventType.isBlank()) spec = spec.and((root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("eventType")), likePattern(eventType), '!'),
                cb.like(cb.lower(root.get("category")), likePattern(eventType), '!')));
        if (outcome != null && !outcome.isBlank()) spec = spec.and((root, query, cb) -> cb.like(cb.lower(root.get("outcome")), likePattern(outcome), '!'));
        if (actorSubject != null && !actorSubject.isBlank()) spec = spec.and((root, query, cb) -> cb.like(cb.lower(root.get("actorSubject")), likePattern(actorSubject), '!'));
        if (runId != null) spec = spec.and((root, query, cb) -> cb.equal(root.get("runId"), runId));
        if (entityId != null && !entityId.isBlank()) spec = spec.and((root, query, cb) -> cb.like(cb.lower(root.get("entityId")), likePattern(entityId), '!'));
        return events.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id"))));
    }

    private static String likePattern(String value) {
        String term = value.trim().toLowerCase(java.util.Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + term + "%";
    }

    @Transactional(readOnly = true)
    public ApplicationAuditEvent findRetained(UUID id) {
        Instant cutoff = clock.instant().minus(retention.retention());
        return events.findById(id).filter(event -> !event.occurredAt().isBefore(cutoff)).orElse(null);
    }

    @Transactional(readOnly = true)
    public Instant collectionStartedAt() {
        return metadata.findById((short) 1).orElseThrow().collectionStartedAt();
    }

    public Duration retention() { return retention.retention(); }
}
