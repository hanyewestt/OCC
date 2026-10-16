package com.csd.occ.ingestion.infrastructure.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.csd.occ.ingestion.domain.TrafficIncidentPollingService;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TrafficIncidentScheduleTest {
    private final TrafficIncidentPollingService polling = mock(TrafficIncidentPollingService.class);

    @Test
    void enabledSchedulePollsTheProvider() {
        new TrafficIncidentSchedule(polling, properties(true)).poll();
        verify(polling).pollOnce();
    }

    @Test
    void disabledScheduleDoesNotPollTheProvider() {
        new TrafficIncidentSchedule(polling, properties(false)).poll();
        verifyNoInteractions(polling);
    }

    private IngestionScheduleProperties properties(boolean trafficIncidentEnabled) {
        return new IngestionScheduleProperties(true, Duration.ofMinutes(1), Duration.ofMinutes(1),
                trafficIncidentEnabled, Duration.ofMinutes(2), Duration.ofSeconds(30),
                Duration.ofHours(1), Duration.ofMinutes(5));
    }
}
