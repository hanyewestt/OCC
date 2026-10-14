package com.csd.occ.disruption.bunching.domain;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsumerSelectionServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-26T05:00:00Z");

    @Mock private ConsumerStateRepository states;
    @Mock private IngestionFeedHeadPort liveHead;
    @Mock private ConsumerState state;

    @Test
    void selectingLiveMovesTheCursorToTheCurrentLiveHead() {
        when(states.findWithLockById(1)).thenReturn(Optional.of(state));
        when(liveHead.currentLiveSequence()).thenReturn(18_000L);
        var service = service();

        service.select("LIVE", null);

        verify(state).selectLive(18_000L, NOW);
        verify(liveHead).currentLiveSequence();
    }

    @Test
    void selectingDemoStartsAtThatRunWithoutReadingOrAdvancingTheLiveFeed() {
        UUID runId = UUID.randomUUID();
        when(states.findWithLockById(1)).thenReturn(Optional.of(state));
        var service = service();

        service.select("DEMO", runId);

        verify(state).selectDemo(runId, NOW);
        verify(liveHead, never()).currentLiveSequence();
        verify(state, never()).selectLive(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
    }

    private ConsumerSelectionService service() {
        return new ConsumerSelectionService(states, liveHead,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
