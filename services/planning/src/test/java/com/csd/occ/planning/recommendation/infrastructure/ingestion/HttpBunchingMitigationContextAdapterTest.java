package com.csd.occ.planning.recommendation.infrastructure.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.csd.occ.planning.advisory.domain.AdvisoryContextPort;
import com.csd.occ.planning.diagnosis.domain.PlanningScope;
import com.csd.occ.planning.diagnosis.domain.PlanningScopePort;
import com.csd.occ.planning.recommendation.domain.BunchingEventStatus;
import com.csd.occ.planning.recommendation.domain.ConfirmedBunchingEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HttpBunchingMitigationContextAdapterTest {

    @Test
    void usesOnlyIncidentBusesInObservationOrderAndItsFollowingBus() {
        UUID runId = UUID.randomUUID();
        var event = event();
        PlanningScopePort scope = () -> new PlanningScope("DEMO", runId);
        var context = org.mockito.Mockito.mock(AdvisoryContextPort.class);
        org.mockito.Mockito.when(context.bunchingGroup(runId, event.bunchingEventId())).thenReturn(
                new AdvisoryContextPort.BunchingGroup(runId, event.bunchingEventId(), "190", 1, "44251",
                        List.of("demo-190-2", "demo-190-1", "demo-190-4", "demo-190-3"), "demo-190-4"));

        var inputs = new HttpBunchingMitigationContextAdapter(scope, context).findFor(event).orElseThrow();

        assertThat(inputs.context().affectedBusReferences())
                .containsExactly("demo-190-2", "demo-190-1", "demo-190-4", "demo-190-3");
        assertThat(inputs.context().followingBusReference()).isEqualTo("demo-190-4");
        assertThat(inputs.context().approvedControlStopCode()).isEqualTo("44251");
    }

    @Test
    void unresolvedOrMismatchedIncidentCannotFallBackToFleetOrFixture() {
        UUID runId = UUID.randomUUID();
        var event = event();
        var context = org.mockito.Mockito.mock(AdvisoryContextPort.class);
        var fallback = org.mockito.Mockito.mock(com.csd.occ.planning.recommendation.infrastructure.fixture.ClasspathBunchingMitigationContextAdapter.class);
        org.mockito.Mockito.when(context.bunchingGroup(runId, event.bunchingEventId())).thenReturn(
                new AdvisoryContextPort.BunchingGroup(runId, event.bunchingEventId(), "972", 1, "44251",
                        List.of("bus-1", "bus-2"), "bus-2"));
        var adapter = new HttpBunchingMitigationContextAdapter(() -> new PlanningScope("DEMO", runId), context, fallback);

        assertThat(adapter.findFor(event)).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(fallback);
        org.mockito.Mockito.verify(context, org.mockito.Mockito.never()).fleet(org.mockito.ArgumentMatchers.any());
    }

    private static ConfirmedBunchingEvent event() {
        return new ConfirmedBunchingEvent(UUID.randomUUID(), "190", 1, "44251", BunchingEventStatus.CONFIRMED,
                600, List.of(45, 45), Instant.parse("2026-11-06T05:54:32Z"), List.of("event-evidence"));
    }
}
