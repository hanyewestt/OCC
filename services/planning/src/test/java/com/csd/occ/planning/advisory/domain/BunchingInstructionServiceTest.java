package com.csd.occ.planning.advisory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.csd.occ.planning.diagnosis.domain.PlanningScope;
import com.csd.occ.planning.diagnosis.domain.PlanningScopePort;
import com.csd.occ.planning.recommendation.domain.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BunchingInstructionServiceTest {
    private static final UUID ID = UUID.fromString("f07c921a-bd3b-4a97-8b17-a384ef1269a7");
    private static final UUID RUN = UUID.fromString("c5934f15-4a65-4e94-87ea-a5a64a7dd4c1");
    private static final UUID CAPTAIN = UUID.fromString("f2b8d1b5-c24c-48bb-8a66-bff7be1e9e73");
    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");

    @Test
    void approvedHoldIsIssuedToTheAssignedCaptainAtTheCurrentControlStop() {
        assertIssued(FollowingBusState.READY_TO_DEPART, MitigationType.HOLD,
                "Hold at City Hall for 120 sec.");
    }

    @Test
    void approvedDwellAdjustmentIsIssuedToTheAssignedCaptainAtTheCurrentControlStop() {
        assertIssued(FollowingBusState.BOARDING_AT_CONTROL_STOP, MitigationType.DWELL_ADJUSTMENT,
                "Extend dwell at City Hall by 75 sec.");
    }

    private void assertIssued(FollowingBusState busState, MitigationType type, String expectedMessage) {
        var event = new ConfirmedBunchingEvent(ID, "190", 1, "44251", BunchingEventStatus.CONFIRMED,
                600, List.of(276, 221), NOW, List.of("arrival-1", "arrival-2"));
        var diagnosis = new BunchingRootCauseDiagnosis(UUID.randomUUID(), "synthetic scenario evidence", null,
                List.of("scenario-fixture"), List.of("Synthetic demonstration only"));
        var mitigation = new ValidatedMitigationContext("SBS5898U", "44251", busState, 120, 75,
                CrowdSafetyStatus.PASSED, MitigationValidationStatus.VALIDATED, List.of("scenario-fixture"));
        var recommendation = new BunchingRecommendationPolicy().evaluate(ID, SourceClassification.SIMULATION,
                event, diagnosis, mitigation, new SimulatedImpactEstimate(221, 341, 38, 24,
                        new BigDecimal("190"), new BigDecimal("128"))).orElseThrow();
        assertThat(recommendation.mitigationType()).isEqualTo(type);
        var record = new RecommendationRecord(ID, "a".repeat(64), UUID.randomUUID(), SourceClassification.SIMULATION,
                event, diagnosis, mitigation, recommendation, NOW);

        var records = mock(RecommendationRecordPort.class);
        var decisions = mock(RecommendationDecisionService.class);
        var instructions = mock(IssuedInstructionRepository.class);
        var current = mock(InstructionService.class);
        var context = mock(AdvisoryContextPort.class);
        var scopes = mock(PlanningScopePort.class);
        var bus = new AdvisoryContextPort.Bus("demo-bus-2", "trip-2", "SBS5898U", "190", 1, CAPTAIN,
                "ASSIGNED", new AdvisoryContextPort.Progress("44241", 1, NOW, 30, List.of(), List.of()));
        var hazard = new AdvisoryContextPort.Hazard(ID, "BUNCHING", "ACTIVE", null, "SIMULATION", RUN,
                null, NOW, null, List.of(), List.of());
        when(records.findByIdempotencyKey(ID)).thenReturn(Optional.of(record));
        when(scopes.current()).thenReturn(new PlanningScope("DEMO", RUN));
        when(context.hazard(ID)).thenReturn(hazard);
        when(context.fleet(RUN)).thenReturn(new AdvisoryContextPort.Fleet(RUN, "RUNNING", List.of(bus), 1));
        when(context.route("190")).thenReturn(new AdvisoryContextPort.Route("190", List.of(
                new AdvisoryContextPort.Operator("SBS Transit", "BUS", List.of(
                        new AdvisoryContextPort.Direction(1, List.of(
                                new AdvisoryContextPort.Stop("44241", "Raffles Place", 1),
                                new AdvisoryContextPort.Stop("44251", "City Hall", 2))))))));
        when(context.limits()).thenReturn(new AdvisoryContextPort.Limits(20, 1800, 5,
                Duration.ofMinutes(5), Duration.ofDays(30)));
        when(current.captain(CAPTAIN.toString())).thenReturn(List.of());
        when(instructions.findForUpdate(ID)).thenReturn(Optional.empty());
        when(instructions.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        var service = new BunchingInstructionService(records, decisions, instructions, current, context,
                scopes, Clock.fixed(NOW, ZoneOffset.UTC));
        var issued = service.approveAndIssue(ID, "controller-subject");

        assertThat(issued.busId()).isEqualTo("demo-bus-2");
        assertThat(issued.captainUserId()).isEqualTo(CAPTAIN);
        assertThat(issued.type().name()).isEqualTo(type.name());
        assertThat(issued.message()).isEqualTo(expectedMessage);
        assertThat(issued.status()).isEqualTo("ISSUED");
        verify(decisions).decide(ID, RecommendationDecisionAction.APPROVED, "controller-subject", List.of(), null);
    }
}
