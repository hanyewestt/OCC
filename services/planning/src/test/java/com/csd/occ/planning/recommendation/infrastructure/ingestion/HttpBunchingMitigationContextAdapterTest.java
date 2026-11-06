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
    void usesInjectedFleetBusIdsForTheAffectedBusesAndTarget() {
        UUID runId = UUID.randomUUID();
        PlanningScopePort scope = () -> new PlanningScope("DEMO", runId);
        AdvisoryContextPort context = new AdvisoryContextPort() {
            @Override public Hazard hazard(UUID disruptionId) { throw new UnsupportedOperationException(); }
            @Override public Fleet fleet(UUID id) {
                return new Fleet(runId, "RUNNING", List.of(
                        bus("demo-190-2"), bus("demo-190-1"), bus("demo-190-3")), 3);
            }
            @Override public Route route(String serviceId) { throw new UnsupportedOperationException(); }
            @Override public Limits limits() { throw new UnsupportedOperationException(); }
        };

        var inputs = new HttpBunchingMitigationContextAdapter(scope, context).findFor(event()).orElseThrow();

        assertThat(inputs.context().affectedBusReferences())
                .containsExactly("demo-190-1", "demo-190-2", "demo-190-3");
        assertThat(inputs.context().followingBusReference()).isEqualTo("demo-190-3");
        assertThat(inputs.context().approvedControlStopCode()).isEqualTo("44251");
    }

    private static AdvisoryContextPort.Bus bus(String id) {
        return new AdvisoryContextPort.Bus(id, id, id, "190", 1, null,
                "CAPTAIN_ASSIGNMENT_UNAVAILABLE", null);
    }

    private static ConfirmedBunchingEvent event() {
        return new ConfirmedBunchingEvent(UUID.randomUUID(), "190", 1, "44251", BunchingEventStatus.CONFIRMED,
                600, List.of(45, 45), Instant.parse("2026-11-06T05:54:32Z"), List.of("event-evidence"));
    }
}
