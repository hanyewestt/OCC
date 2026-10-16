package com.csd.occ.ingestion.domain;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
class RetentionBoundaryTest {
    @Test void closedDemoExpiresOnlyStrictlyBeforeCutoff() {
        Instant cutoff = Instant.parse("2026-10-13T00:00:00Z");
        DemoRun run = new DemoRun(java.util.UUID.randomUUID(), cutoff.minusSeconds(1), "route190-bunching-v1", 190);
        assertThat(run.expiredAt(cutoff)).isFalse();
        run.close(cutoff);
        assertThat(run.expiredAt(cutoff)).isFalse();
        assertThat(run.expiredAt(cutoff.plusNanos(1))).isTrue();
    }
    @Test void historyReadUsesConfiguredFetchTimeCutoffForAlternateDurations() {
        var arrivals = mock(BusArrivalObservationRepository.class);
        var settings = mock(PollingConfigurationPort.class);
        Instant now = Instant.parse("2026-10-13T12:00:00Z");
        var query = new IngestionQueryService(arrivals, mock(FeedStatusRepository.class), mock(PollRunRepository.class),
                mock(ReferenceTopologyPort.class), settings, Clock.fixed(now, ZoneOffset.UTC));
        for (Duration window : java.util.List.of(Duration.ofHours(3), Duration.ofHours(12))) {
            when(settings.retention()).thenReturn(window);
            query.arrivalHistory(null, null, now.minus(Duration.ofDays(2)), null, PageRequest.of(0, 50));
            verify(arrivals).findHistory(null, null, now.minus(window), null, PageRequest.of(0, 50));
        }
    }
}
