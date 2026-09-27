package com.csd.occ.bunching.domain;

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

    @Mock private ConsumerSelectionRepository selections;
    @Mock private IngestionFeedCursorRepository cursors;
    @Mock private IngestionFeedHeadPort liveHead;
    @Mock private IngestionFeedCursor cursor;
    @Mock private ConsumerSelection selection;

    @Test
    void selectingLiveMovesTheCursorToTheCurrentLiveHead() {
        when(cursors.findById(1)).thenReturn(Optional.of(cursor));
        when(selections.findById(1)).thenReturn(Optional.of(selection));
        when(liveHead.currentLiveSequence()).thenReturn(18_000L);
        var service = service();

        service.select("LIVE", null);

        verify(cursor).selectLive(18_000L);
        verify(selection).select("LIVE", null, NOW);
        verify(liveHead).currentLiveSequence();
    }

    @Test
    void selectingDemoStartsAtThatRunWithoutReadingOrAdvancingTheLiveFeed() {
        UUID runId = UUID.randomUUID();
        when(cursors.findById(1)).thenReturn(Optional.of(cursor));
        when(selections.findById(1)).thenReturn(Optional.of(selection));
        var service = service();

        service.select("DEMO", runId);

        verify(cursor).selectDemo(runId);
        verify(selection).select("DEMO", runId, NOW);
        verify(liveHead, never()).currentLiveSequence();
        verify(cursor, never()).selectLive(org.mockito.ArgumentMatchers.anyLong());
    }

    private ConsumerSelectionService service() {
        return new ConsumerSelectionService(selections, cursors, liveHead,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
