package com.csd.occ.ingestion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class DemoFleetServiceTest {
    private final DemoRunRepository runs = mock(DemoRunRepository.class);
    private final DemoVehicleAssignmentRepository assignments = mock(DemoVehicleAssignmentRepository.class);
    private final BusArrivalObservationRepository arrivals = mock(BusArrivalObservationRepository.class);
    private final ReferenceTopologyPort topology = mock(ReferenceTopologyPort.class);
    private final DemoFleetService fleet = new DemoFleetService(runs, assignments, arrivals, topology);

    @Test
    void exposesOnlyActiveRunAssignmentsWithExplicitUnavailableProgress() {
        UUID runId = UUID.randomUUID();
        UUID captain = UUID.randomUUID();
        DemoRun run = new DemoRun(runId, Instant.EPOCH, "multi-route-bunching-v2", 190);
        run.startReplay();
        var assigned = new DemoVehicleAssignment(new DemoVehicleAssignmentId(runId, "demo-190-b"),
                "190", 1, "SBS5898U", "SBST", "DD", "DEMO-CAPTAIN", captain);
        var unassigned = new DemoVehicleAssignment(new DemoVehicleAssignmentId(runId, "demo-190-a"),
                "190", 1, "SBS8027Y", "SBST", "DD", "DEMO-CAPTAIN-OTHER");
        when(runs.findById(runId)).thenReturn(Optional.of(run));
        when(assignments.findByIdDemoRunIdOrderByBusId(runId, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(unassigned, assigned)));
        var result = fleet.find(runId, 0, 20);
        assertThat(result.demoRunId()).isEqualTo(runId);
        assertThat(result.items().getLast().captainUserId()).contains(captain);
        assertThat(result.items().getFirst().assignmentStatus()).isEqualTo("CAPTAIN_ASSIGNMENT_UNAVAILABLE");
        assertThat(result.items()).allSatisfy(bus -> assertThat(bus.progress()).isEmpty());
        run.close(Instant.EPOCH.plusSeconds(1));
        assertThat(fleet.find(runId, 0, 20).items()).isEmpty();
        verify(assignments, times(1)).findByIdDemoRunIdOrderByBusId(any(), any());
    }

    @Test
    void rejectsUnknownRunsAndUnboundedPages() {
        assertThatThrownBy(() -> fleet.find(UUID.randomUUID(), 0, 101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fleet.find(UUID.randomUUID(), 0, 20)).isInstanceOf(DemoRunNotFoundException.class);
    }
}
