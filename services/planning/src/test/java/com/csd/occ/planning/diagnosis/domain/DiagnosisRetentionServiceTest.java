package com.csd.occ.planning.diagnosis.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class DiagnosisRetentionServiceTest {
    @Test
    void returnsRowsDeletedFromCompletedDeliveryAndRequestTables() {
        var requests = mock(DiagnosisRequestRepository.class);
        var deliveries = mock(RecommendationDeliveryRepository.class);
        when(deliveries.deleteCompletedBefore(any())).thenReturn(4);
        when(requests.deleteUnreferencedTerminalBefore(any())).thenReturn(3);
        var service = new DiagnosisRetentionService(requests, deliveries,
                Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"), ZoneOffset.UTC));

        assertThat(service.purge(Duration.ofDays(7))).isEqualTo(7L);
    }
}
