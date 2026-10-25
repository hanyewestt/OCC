package com.csd.occ.ingestion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HazardPollingServiceTest {
    @Test
    void failedFeedDoesNotPreventAnotherFeedFromRecordingACompleteEmptySnapshot() {
        var provider = mock(HazardFeedPort.class);
        var evidence = mock(HazardEvidencePort.class);
        var polls = mock(PollRunRepository.class);
        var statuses = mock(FeedStatusRepository.class);
        var raw = mock(RawObservationRepository.class);
        when(provider.fetch(HazardKind.FLOOD)).thenThrow(new IllegalStateException("synthetic failure"));
        when(provider.fetch(HazardKind.ROADWORKS)).thenReturn(new HazardFeedPort.Batch(200, List.of("{\"value\":[]}"), List.of(), List.of()));
        when(evidence.encodePages(any())).thenReturn("[\"empty snapshot\"]");
        when(evidence.checksum(any())).thenReturn("a".repeat(64));
        var service = new HazardPollingService(provider, evidence, polls, raw, mock(HazardObservationRepository.class),
                statuses, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        service.poll(HazardKind.FLOOD, Duration.ofMinutes(10));
        service.poll(HazardKind.ROADWORKS, Duration.ofDays(2));
        var capture = ArgumentCaptor.forClass(FeedStatus.class);
        verify(statuses, times(2)).save(capture.capture());
        assertThat(capture.getAllValues()).extracting(FeedStatus::getStatus).containsExactly("FAILED", "HEALTHY");
        assertThat(capture.getAllValues().getFirst().getLastSuccessAt()).isNull();
        assertThat(capture.getAllValues().getLast().getLastSuccessAt()).isEqualTo(Instant.EPOCH);
        verify(raw, times(1)).save(any());
    }
}
