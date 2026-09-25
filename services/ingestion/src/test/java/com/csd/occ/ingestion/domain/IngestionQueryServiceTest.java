package com.csd.occ.ingestion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class IngestionQueryServiceTest {
    @Test
    void positionBecomesStaleAtTheConfiguredDeadline() {
        PollingConfigurationPort configuration = mock(PollingConfigurationPort.class);
        when(configuration.staleAfterSeconds()).thenReturn(180);
        IngestionQueryService service = new IngestionQueryService(mock(BusArrivalObservationRepository.class),
                mock(FeedStatusRepository.class), mock(PollRunRepository.class),
                mock(ReferenceTopologyPort.class), configuration, Clock.systemUTC());
        Instant fetchedAt = Instant.parse("2026-09-25T00:00:00Z");

        assertThat(service.isStale(fetchedAt, fetchedAt.plusSeconds(179))).isFalse();
        assertThat(service.isStale(fetchedAt, fetchedAt.plusSeconds(180))).isTrue();
    }
}
