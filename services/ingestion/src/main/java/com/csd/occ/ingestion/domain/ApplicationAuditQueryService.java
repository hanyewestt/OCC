package com.csd.occ.ingestion.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/** Read-only queries over immutable application audit snapshots. */
@Service
public class ApplicationAuditQueryService {
    private static final Duration DEFAULT_WINDOW = Duration.ofHours(24);
    private static final int MAX_SIZE = 100;

    private final ApplicationAuditEventRepository events;
    private final ApplicationAuditRetentionService retention;
    private final AuditRetentionPolicy retentionPolicy;
    private final Clock clock;

    public ApplicationAuditQueryService(ApplicationAuditEventRepository events,
            ApplicationAuditRetentionService retention, AuditRetentionPolicy retentionPolicy, Clock clock) {
        this.events = events;
        this.retention = retention;
        this.retentionPolicy = retentionPolicy;
        this.clock = clock;
    }

    public SearchResult search(AuditFilter filter, int page, int size) {
        if (filter == null) throw new IllegalArgumentException("Audit filters are required");
        if (page < 0 || size < 1 || size > MAX_SIZE) throw new IllegalArgumentException("Invalid audit page or size");
        Instant now = clock.instant();
        Instant to = filter.to() == null ? now : filter.to();
        Instant requestedFrom = filter.from() == null ? now.minus(DEFAULT_WINDOW) : filter.from();
        if (requestedFrom.isAfter(to)) throw new IllegalArgumentException("from must be before or equal to to");
        Instant cutoff = now.minus(retentionPolicy.retention());
        Instant from = requestedFrom.isBefore(cutoff) ? cutoff : requestedFrom;
        var pageRequest = PageRequest.of(page, size);
        var results = events.search(likePattern(filter.category()), likePattern(filter.eventType()),
                likePattern(filter.outcome()), likePattern(filter.actorSubject()), filter.runId(),
                likePattern(filter.entityId()), from, to, pageRequest);
        Instant collectionStartedAt = retention.collectionStartedAt();
        return new SearchResult(results, retentionPolicy.retention().toSeconds(), collectionStartedAt);
    }

    public java.util.Optional<ApplicationAuditEvent> detail(UUID id) {
        Instant cutoff = clock.instant().minus(retentionPolicy.retention());
        return events.findByIdAndOccurredAtGreaterThanEqual(id, cutoff);
    }

    private static String likePattern(String value) {
        if (value == null || value.isBlank()) return null;
        String term = value.trim().toLowerCase(Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + term + "%";
    }

    public record AuditFilter(String category, String eventType, String outcome, String actorSubject,
            UUID runId, String entityId, Instant from, Instant to) { }

    public record SearchResult(Page<ApplicationAuditEvent> page, long retentionSeconds, Instant collectionStartedAt) { }
}
