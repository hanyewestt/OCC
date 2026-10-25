package com.csd.occ.planning.advisory.domain;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.csd.occ.planning.advisory.domain.AdvisoryContextPort.Fleet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InstructionRunReconciliationServiceTest {
    @Test
    void retentionReconciliationCancelsClosedRunsWithoutDependingOnHistoryReads() {
        var closedRun = UUID.randomUUID();
        var activeRun = UUID.randomUUID();
        var instructions = mock(IssuedInstructionRepository.class);
        when(instructions.findActiveDemoRunIds()).thenReturn(List.of(closedRun, activeRun));
        var context = mock(AdvisoryContextPort.class);
        when(context.fleet(closedRun)).thenReturn(new Fleet(closedRun, "CLOSED", List.of(), 0));
        when(context.fleet(activeRun)).thenReturn(new Fleet(activeRun, "RUNNING", List.of(), 0));
        var reconciliation = mock(AdvisoryReconciliationService.class);
        var service = new InstructionRunReconciliationService(instructions, context, reconciliation);

        service.reconcileClosedRuns();

        verify(reconciliation).runClosed(closedRun);
        verify(reconciliation, never()).runClosed(activeRun);
    }
}
