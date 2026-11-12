package com.csd.occ.planning.advisory.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.csd.occ.planning.domain.audit.ApplicationAuditEvent;
import com.csd.occ.planning.domain.audit.ApplicationAuditEventRepository;

@Service
public class PublicNoticeService {
    private final PublicNoticeRepository notices;
    private final AdvisoryContextPort context;
    private final AdvisoryDraftPort drafts;
    private final Clock clock;
    private final ApplicationAuditEventRepository auditEvents;
    public PublicNoticeService(PublicNoticeRepository notices, AdvisoryContextPort context, AdvisoryDraftPort drafts,
            Clock clock, ApplicationAuditEventRepository auditEvents) {
        this.notices = notices; this.context = context; this.drafts = drafts; this.clock = clock; this.auditEvents = auditEvents;
    }
    public AdvisoryDraftPort.NoticeSuggestion suggest(UUID disruptionId) {
        var hazard = context.hazard(disruptionId);
        if ("CLEARED".equals(hazard.status())) throw new AdvisoryConflictException("Source is no longer current");
        return drafts.suggestNotice(hazard);
    }
    @Transactional
    public PublicNotice draft(UUID id, UUID disruptionId, String title, String message, List<String> services,
            List<String> locations, Instant from, Instant until, String actor) {
        if (id == null || disruptionId == null || title == null || title.isBlank() || title.length() > 160
                || message == null || message.isBlank() || message.length() > 2000 || from == null || until == null
                || !until.isAfter(from) || services == null || locations == null
                || services.size() > 100 || locations.size() > 100
                || java.util.stream.Stream.concat(services.stream(), locations.stream()).anyMatch(v -> v == null || v.isBlank() || v.length() > 250))
            throw new IllegalArgumentException("Valid notice content and validity interval required");
        var existing = notices.findById(id);
        if (existing.isPresent()) {
            var n = existing.get();
            if (n.disruptionId().equals(disruptionId) && n.title().equals(title) && n.message().equals(message)
                    && n.services().equals(services) && n.locations().equals(locations)
                    && n.effectiveFrom().equals(from) && n.effectiveUntil().equals(until)) return n;
            throw new AdvisoryConflictException("Notice identity already has different content");
        }
        if (!until.isAfter(clock.instant())) throw new IllegalArgumentException("Notice validity must not have expired");
        var hazard = context.hazard(disruptionId);
        if ("CLEARED".equals(hazard.status())) throw new AdvisoryConflictException("Source is no longer current");
        if (hazard.facts().effectiveUntil() != null && until.isAfter(hazard.facts().effectiveUntil()))
            throw new IllegalArgumentException("Notice validity exceeds source expiry");
        if (hazard.facts().instruction() != null && !hazard.facts().instruction().isBlank()
                && !message.contains(hazard.facts().instruction().strip()))
            throw new IllegalArgumentException("Notice must preserve the source instruction");
        var saved = notices.save(new PublicNotice(id, hazard, title, message, services, locations, from, until, actor, clock.instant()));
        recordNotice(saved, "NOTICE_DRAFT_CREATED", actor);
        return saved;
    }
    @Transactional
    public PublicNotice publish(UUID id, String actor) {
        var notice = notices.findForUpdate(id).orElseThrow(AdvisoryNotFoundException::new);
        if ("PUBLISHED".equals(notice.status())) return notice;
        validatePublicationSource(notice);
        notice.publish(actor, clock.instant().truncatedTo(ChronoUnit.MICROS)); recordNotice(notice, "NOTICE_PUBLISHED", actor); return notice;
    }
    @Transactional
    public PublicNotice withdraw(UUID id) { return withdraw(id, null); }
    @Transactional
    public PublicNotice withdraw(UUID id, String actor) {
        var notice = notices.findForUpdate(id).orElseThrow(AdvisoryNotFoundException::new);
        if (!"WITHDRAWN".equals(notice.status())) {
            notice.withdraw(clock.instant().truncatedTo(ChronoUnit.MICROS)); recordNotice(notice, "NOTICE_WITHDRAWN", actor);
        }
        return notice;
    }
    @Transactional
    public void withdrawSource(UUID disruptionId) {
        for (var notice : notices.findNotWithdrawnByDisruptionIdForUpdate(disruptionId)) {
            notice.withdraw(clock.instant().truncatedTo(ChronoUnit.MICROS));
            recordNotice(notice, "NOTICE_WITHDRAWN", null);
        }
    }
    @Transactional
    public PublicNotice replace(UUID previousId, UUID nextId, UUID disruptionId, String title, String message,
            List<String> services, List<String> locations, Instant from, Instant until, String actor) {
        var previous = notices.findForUpdate(previousId).orElseThrow(AdvisoryNotFoundException::new);
        if (previousId.equals(nextId) || !previous.disruptionId().equals(disruptionId))
            throw new IllegalArgumentException("Replacement needs a new identity and the same source");
        if (previous.replacementId() != null) {
            if (!previous.replacementId().equals(nextId)) throw new AdvisoryConflictException("Notice has already been replaced");
            // A retry must not revalidate changed source facts or resurrect a withdrawn correction.
            return draft(nextId, disruptionId, title, message, services, locations, from, until, actor);
        }
        if (!"PUBLISHED".equals(previous.status()))
            throw new AdvisoryConflictException("Only a published notice can be replaced");
        if (from == null || from.isAfter(clock.instant()))
            throw new IllegalArgumentException("A correction must take effect immediately");
        var replacement = draft(nextId, disruptionId, title, message, services, locations, from, until, actor);
        if (!"DRAFT".equals(replacement.status())) throw new AdvisoryConflictException("Replacement must be an unpublished draft");
        validatePublicationSource(replacement);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        replacement.publish(actor, now); previous.replace(nextId, now);
        recordNotice(previous, "NOTICE_REPLACED", actor); recordNotice(replacement, "NOTICE_PUBLISHED", actor);
        return replacement;
    }
    public Page<PublicNotice> managed(int page, int size) { validatePage(page, size); return notices.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size)); }
    public Page<PublicNotice> current(String classification, UUID runId, int page, int size) {
        validatePage(page, size);
        if ("ALL".equals(classification) && runId == null)
            return notices.findAllCurrent(clock.instant(), PageRequest.of(page, size));
        if (!(classification.equals("OBSERVATION_ONLY") && runId == null || classification.equals("SIMULATION") && runId != null))
            throw new IllegalArgumentException("Simulation notice reads require a run ID; observation-only reads do not accept one");
        return notices.findCurrent(classification, runId, clock.instant(), PageRequest.of(page, size));
    }
    private void validatePage(int page, int size) { if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid notice page"); }
    private void recordNotice(PublicNotice notice, String type, String actor) {
        var now = clock.instant();
        var details = new java.util.LinkedHashMap<String, Object>();
        details.put("status", notice.status()); details.put("title", notice.title());
        details.put("message", notice.message()); details.put("services", notice.services());
        details.put("locations", notice.locations());
        details.put("effectiveFrom", notice.effectiveFrom() == null ? null : notice.effectiveFrom().toString());
        details.put("effectiveUntil", notice.effectiveUntil() == null ? null : notice.effectiveUntil().toString());
        details.put("publishedAt", notice.publishedAt() == null ? null : notice.publishedAt().toString());
        details.put("withdrawnAt", notice.withdrawnAt() == null ? null : notice.withdrawnAt().toString());
        details.put("replacementId", notice.replacementId() == null ? null : notice.replacementId().toString());
        auditEvents.save(new ApplicationAuditEvent(UUID.randomUUID(), now, now, "ADVISORIES", type,
                notice.status(), actor, actor == null ? "SYSTEM" : "CONTROLLER", "PUBLIC_NOTICE",
                notice.id().toString(), notice.demoRunId(), null, null, notice.disruptionId().toString(),
                "Public notice state changed", details,
                "public-notice:" + notice.id() + ":" + type));
    }
    private void validatePublicationSource(PublicNotice notice) {
        var source = context.hazard(notice.disruptionId());
        if ("CLEARED".equals(source.status())) throw new AdvisoryConflictException("Source was withdrawn");
        if (source.facts().effectiveUntil() != null && notice.effectiveUntil().isAfter(source.facts().effectiveUntil()))
            throw new AdvisoryConflictException("Source expiry changed; prepare a corrected notice");
        if (source.facts().instruction() != null && !source.facts().instruction().isBlank()
                && !notice.message().contains(source.facts().instruction().strip()))
            throw new AdvisoryConflictException("Source instruction changed; prepare a corrected notice");
    }
}
