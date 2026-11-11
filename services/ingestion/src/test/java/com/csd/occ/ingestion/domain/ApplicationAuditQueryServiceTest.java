package com.csd.occ.ingestion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.csd.occ.ingestion.infrastructure.config.AuditRetentionProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class ApplicationAuditQueryServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-11T00:00:00Z");
    private final ApplicationAuditEventRepository events = mock(ApplicationAuditEventRepository.class);
    private final ApplicationAuditRetentionService retention = mock(ApplicationAuditRetentionService.class);
    private final AuditRetentionProperties properties = new AuditRetentionProperties(Duration.ofDays(90));
    private final ApplicationAuditQueryService service = new ApplicationAuditQueryService(events, retention,
            properties, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void defaultsToLast24HoursAndRetainsFiltersAndPageMetadata() {
        var filter = new ApplicationAuditQueryService.AuditFilter(" OPERATIONS ", "FEED_POLL_COMPLETED", "FAILURE",
                "Admin-Subject", UUID.randomUUID(), "POLL-1", null, null);
        var pageable = PageRequest.of(2, 25);
        when(events.search("%operations%", "%feed!_poll!_completed%", "%failure%", "%admin-subject%", filter.runId(),
                "%poll-1%", NOW.minus(Duration.ofHours(24)), NOW, pageable))
                .thenReturn(new PageImpl<>(java.util.List.of(), pageable, 0));
        when(retention.collectionStartedAt()).thenReturn(NOW.minus(Duration.ofDays(20)));

        var result = service.search(filter, 2, 25);

        assertThat(result.page().getNumber()).isEqualTo(2);
        assertThat(result.page().getSize()).isEqualTo(25);
        assertThat(result.retentionSeconds()).isEqualTo(Duration.ofDays(90).toSeconds());
        assertThat(result.collectionStartedAt()).isEqualTo(NOW.minus(Duration.ofDays(20)));
        verify(events).search("%operations%", "%feed!_poll!_completed%", "%failure%", "%admin-subject%", filter.runId(),
                "%poll-1%", NOW.minus(Duration.ofHours(24)), NOW, pageable);
    }

    @Test
    void clampsExplicitSearchToTheRetainedWindow() {
        Instant requestedFrom = NOW.minus(Duration.ofDays(100));
        Instant cutoff = NOW.minus(Duration.ofDays(90));
        var filter = new ApplicationAuditQueryService.AuditFilter(null, null, null, null, null, null, requestedFrom, NOW);
        var pageable = PageRequest.of(0, 20);
        when(events.search(null, null, null, null, null, null, cutoff, NOW, pageable))
                .thenReturn(new PageImpl<>(java.util.List.of(), pageable, 0));
        when(retention.collectionStartedAt()).thenReturn(NOW.minus(Duration.ofDays(30)));

        service.search(filter, 0, 20);

        verify(events).search(null, null, null, null, null, null, cutoff, NOW, pageable);
    }

    @Test
    void rejectsReversedTimeRangesBeforeQuerying() {
        var filter = new ApplicationAuditQueryService.AuditFilter(null, null, null, null, null, null,
                NOW, NOW.minusSeconds(1));

        assertThatThrownBy(() -> service.search(filter, 0, 20)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(events, retention);
    }

    @Test
    void detailQueryEnforcesTheRetentionCutoff() {
        UUID eventId = UUID.randomUUID();
        when(events.findByIdAndOccurredAtGreaterThanEqual(eventId, NOW.minus(Duration.ofDays(90))))
                .thenReturn(java.util.Optional.empty());

        assertThat(service.detail(eventId)).isEmpty();
        verify(events).findByIdAndOccurredAtGreaterThanEqual(eventId, NOW.minus(Duration.ofDays(90)));
    }
}
