package com.csd.occ.ingestion.infrastructure.config;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.csd.occ.ingestion.domain.BusArrivalService;
import com.csd.occ.ingestion.domain.PollTargetService;
import com.csd.occ.ingestion.domain.ReferenceImportService;
import com.csd.occ.ingestion.domain.ReferenceTopologyPort;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class BusArrivalScheduleTest {
    private final BusArrivalService arrivals = mock(BusArrivalService.class);
    private final ReferenceTopologyPort topology = mock(ReferenceTopologyPort.class);
    private final ReferenceImportService imports = mock(ReferenceImportService.class);
    private final PollTargetService targets = mock(PollTargetService.class);
    private final IngestionScheduleProperties enabled = new IngestionScheduleProperties(true,
            Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofHours(1), Duration.ofMinutes(5));
    private final BusArrivalSchedule schedule = new BusArrivalSchedule(arrivals, topology, imports, targets, enabled);

    @Test
    void missingTopologyIsRestoredBeforePolling() {
        when(topology.activeVersion()).thenReturn(java.util.Optional.empty());
        schedule.poll();
        verify(imports).refresh();
        verify(targets).seed();
        verify(arrivals).poll();
    }

    @Test
    void failedTopologyRestoreSkipsPolling() {
        when(topology.activeVersion()).thenReturn(java.util.Optional.empty());
        doThrow(new IllegalStateException("synthetic reference failure")).when(imports).refresh();
        schedule.poll();
        verifyNoInteractions(arrivals, targets);
    }

    @Test
    void disabledScheduleDoesNotReadTopologyOrCallProviders() {
        var disabled = new IngestionScheduleProperties(false,
                Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofHours(1), Duration.ofMinutes(5));
        new BusArrivalSchedule(arrivals, topology, imports, targets, disabled).poll();
        verifyNoInteractions(arrivals, topology, imports, targets);
    }
}
