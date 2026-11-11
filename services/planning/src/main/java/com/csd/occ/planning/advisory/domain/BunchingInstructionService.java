package com.csd.occ.planning.advisory.domain;

import com.csd.occ.planning.diagnosis.domain.PlanningScopePort;
import com.csd.occ.planning.domain.audit.ApplicationAuditEvent;
import com.csd.occ.planning.domain.audit.ApplicationAuditEventRepository;
import com.csd.occ.planning.recommendation.domain.*;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Explicitly issues only typed, validated HOLD/DWELL recommendations; legacy free text remains history. */
@Service
public class BunchingInstructionService {
    private final RecommendationRecordPort recommendations;
    private final RecommendationDecisionService decisions;
    private final IssuedInstructionRepository instructions;
    private final InstructionService currentInstructions;
    private final AdvisoryContextPort context;
    private final PlanningScopePort scopes;
    private final Clock clock;
    private final ApplicationAuditEventRepository auditEvents;
    private final AssignmentBusGuards guards;
    public BunchingInstructionService(RecommendationRecordPort recommendations, RecommendationDecisionService decisions,
            IssuedInstructionRepository instructions, InstructionService currentInstructions, AdvisoryContextPort context,
            PlanningScopePort scopes, Clock clock, ApplicationAuditEventRepository auditEvents, AssignmentBusGuards guards) {
        this.recommendations = recommendations; this.decisions = decisions; this.instructions = instructions;
        this.currentInstructions = currentInstructions; this.context = context; this.scopes = scopes; this.clock = clock;
        this.auditEvents = auditEvents; this.guards = guards;
    }
    @Transactional
    public IssuedInstruction approveAndIssue(UUID id, PlanningActor actor) { return approveAndIssue(id, actor, null); }
    @Transactional
    public IssuedInstruction approveAndIssue(UUID id, PlanningActor actor, UUID replaceInstructionId) {
        actor.require(PlanningActor.Role.CONTROLLER);
        return issue(id, actor.subject(), RecommendationDecisionAction.APPROVED, null, 0, null, replaceInstructionId);
    }
    @Transactional
    public IssuedInstruction rejectAndIssue(UUID id, PlanningActor actor, MitigationType type, int seconds, String rationale) {
        actor.require(PlanningActor.Role.CONTROLLER);
        var record = recommendations.findByIdempotencyKey(id).orElseThrow(AdvisoryNotFoundException::new);
        var target = record.recommendation().targetBusReference();
        return rejectAndIssue(id, actor, List.of(new ReplacementBusInstruction(target, type, seconds)), rationale).getFirst();
    }
    @Transactional
    public List<IssuedInstruction> rejectAndIssue(UUID id, PlanningActor actor, List<ReplacementBusInstruction> requested, String rationale) {
        actor.require(PlanningActor.Role.CONTROLLER);
        var record = recommendations.findByIdempotencyKey(id).orElseThrow(AdvisoryNotFoundException::new);
        var normalized = requested.stream().map(item -> item.busReference() == null || item.busReference().isBlank()
                ? new ReplacementBusInstruction(record.recommendation().targetBusReference(), item.type(), item.seconds()) : item).toList();
        return issue(id, actor.subject(), RecommendationDecisionAction.REJECTED, normalized, rationale, null);
    }
    private IssuedInstruction issue(UUID id, String actor, RecommendationDecisionAction decisionAction,
            MitigationType requestedType, int requestedSeconds, String rationale, UUID replaceInstructionId) {
        List<ReplacementBusInstruction> requested = requestedType == null ? List.of()
                : List.of(new ReplacementBusInstruction("", requestedType, requestedSeconds));
        return issue(id, actor, decisionAction, requested, rationale, replaceInstructionId).getFirst();
    }
    private List<IssuedInstruction> issue(UUID id, String actor, RecommendationDecisionAction decisionAction,
            List<ReplacementBusInstruction> requested, String rationale, UUID replaceInstructionId) {
        var record = recommendations.findByIdempotencyKey(id).orElseThrow(AdvisoryNotFoundException::new);
        var priorDelivery = replayRecordedDecision(record, decisionAction, requested, rationale, replaceInstructionId);
        if (priorDelivery != null) return priorDelivery;
        var recommendation = record.recommendation();
        if (record.classification() != SourceClassification.SIMULATION || recommendation.status() != RecommendationStatus.RECOMMENDED
                || !List.of(MitigationType.HOLD, MitigationType.DWELL_ADJUSTMENT).contains(recommendation.mitigationType()))
            throw new AdvisoryConflictException("Only validated synthetic HOLD/DWELL recommendations can be issued");
        var target = recommendation.targetBusReference();
        var initialActions = decisionAction == RecommendationDecisionAction.APPROVED
                ? List.of(new ReplacementBusInstruction(target, recommendation.mitigationType(), recommendation.adjustmentSeconds()))
                : requested;
        var actions = initialActions.stream().sorted(java.util.Comparator.comparing(action -> !action.busReference().equals(target))).toList();
        if (actions.isEmpty() || actions.size() > 3 || actions.stream().map(ReplacementBusInstruction::busReference).distinct().count() != actions.size())
            throw new AdvisoryConflictException("One unique instruction per affected bus is required");
        var allowedReferences = record.mitigationContext().affectedBusReferences();
        if (!actions.stream().map(ReplacementBusInstruction::busReference).allMatch(allowedReferences::contains)
                || actions.stream().noneMatch(action -> action.busReference().equals(target)))
            throw new AdvisoryConflictException("Instructions must include the approved target and only affected buses");
        var permittedType = record.mitigationContext().followingBusState() == FollowingBusState.READY_TO_DEPART
                ? MitigationType.HOLD : MitigationType.DWELL_ADJUSTMENT;
        int maxSeconds = Math.min(record.bunchingEvent().expectedHeadwaySeconds()
                - record.bunchingEvent().observedHeadwaySeconds().getLast(),
                permittedType == MitigationType.HOLD ? record.mitigationContext().maximumHoldSeconds()
                        : record.mitigationContext().maximumDwellAdjustmentSeconds());
        final int allowedSeconds = Math.min(maxSeconds, 120);
        if (actions.stream().anyMatch(action -> action.type() != permittedType || action.seconds() < 1 || action.seconds() > allowedSeconds))
            throw new AdvisoryConflictException("A bus instruction exceeds the validated action or duration limits");
        var run = scopes.current().demoRunId();
        if (run == null) throw new AdvisoryConflictException("No active synthetic run");
        var discoveryFleet = context.fleet(run);
        if (!List.of("RUNNING", "REPLAY_COMPLETE").contains(discoveryFleet.status())) throw new AdvisoryConflictException("Run is closed");
        var discoveredBuses = resolveBuses(record, actions, discoveryFleet);
        if (replaceInstructionId != null) {
            var predecessorKey = instructions.findKeyById(replaceInstructionId).orElseThrow(AdvisoryNotFoundException::new);
            if (!run.equals(predecessorKey.getDemoRunId()) || discoveredBuses.values().stream()
                    .noneMatch(bus -> bus.busId().equals(predecessorKey.getBusId())))
                throw new AdvisoryConflictException("Replacement instruction belongs to another bus or run");
        }
        guards.requireAvailableForIssuance(guards.lockAll(discoveredBuses.values().stream()
                .map(bus -> new AssignmentBusGuards.Key(run, bus.busId())).toList()));

        if (!run.equals(scopes.current().demoRunId())) throw new AdvisoryConflictException("Simulation scope changed");
        record = recommendations.findByIdempotencyKey(id).orElseThrow(AdvisoryNotFoundException::new);
        recommendation = record.recommendation();
        final RecommendationRecord lockedRecord = record;
        final ValidatedMitigationRecommendation lockedRecommendation = recommendation;
        var fleet = context.fleet(run);
        if (!List.of("RUNNING", "REPLAY_COMPLETE").contains(fleet.status())) throw new AdvisoryConflictException("Run is closed");
        var currentBuses = resolveBuses(record, actions, fleet);
        if (!discoveredBuses.values().stream().map(AdvisoryContextPort.Bus::busId).sorted().toList()
                .equals(currentBuses.values().stream().map(AdvisoryContextPort.Bus::busId).sorted().toList()))
            throw new AdvisoryConflictException("Affected bus assignments changed; refresh the recommendation");

        var hazard = context.hazard(record.bunchingEvent().bunchingEventId());
        if (!"ACTIVE".equals(hazard.status()) || !run.equals(hazard.demoRunId()))
            throw new AdvisoryConflictException("Bunching episode is no longer active in this run");
        var primary = actions.stream().filter(action -> action.busReference().equals(target)).findFirst().orElseThrow();
        var candidate = new MitigationCandidate(CandidateMitigationType.valueOf(primary.type().name()), target,
                recommendation.controlStopCode(), primary.seconds(), rationale == null ? "Controller approved validated recommendation" : rationale);
        if (!(new BunchingMitigationCandidateValidator().validate(new MitigationCandidateRequest(record.classification(),
                record.bunchingEvent(), record.diagnosis(), record.mitigationContext()), candidate)
                instanceof MitigationCandidateValidationResult.Accepted))
            throw new AdvisoryConflictException("Stored recommendation no longer satisfies HOLD/DWELL safety rules");
        final String controlStopCode = recommendation.controlStopCode();
        var auditInstructions = actions.stream().map(action -> new ControllerBusInstruction(action.busReference(),
                action.type() == MitigationType.HOLD ? "HOLD FOR " + action.seconds() + "s at next stop"
                        : "EXTEND DWELL BY " + action.seconds() + "s at stop " + controlStopCode)).toList();
        decisions.decide(id, decisionAction, actor,
                decisionAction == RecommendationDecisionAction.APPROVED ? List.of() : auditInstructions, rationale);
        String decisionSourceKey = "bunching-decision:" + id;
        if (!auditEvents.existsBySourceKey(decisionSourceKey)) {
            var details = new java.util.LinkedHashMap<String, Object>();
            details.put("decision", decisionAction.name());
            details.put("bunchingEventId", record.bunchingEvent().bunchingEventId().toString());
            details.put("instructions", auditInstructions.stream().map(item -> java.util.Map.of(
                    "busReference", item.busReference(), "instruction", item.instruction())).toList());
            details.put("rationale", rationale);
            details.put("candidate", java.util.Map.of("type", candidate.mitigationType().name(),
                    "targetBusReference", candidate.targetBusReference(), "controlStopCode", candidate.controlStopCode(),
                    "durationSeconds", candidate.requestedAdjustmentSeconds(), "rationale", candidate.rationale()));
            auditEvents.save(new ApplicationAuditEvent(UUID.nameUUIDFromBytes(decisionSourceKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    clock.instant(), clock.instant(), "ADVISORIES", "RECOMMENDATION_DECIDED", decisionAction.name(),
                    actor, "CONTROLLER", "BUNCHING_RECOMMENDATION", id.toString(), scopes.current().demoRunId(),
                    target, null, record.bunchingEvent().bunchingEventId().toString(),
                    "Bunching recommendation decided", details, decisionSourceKey));
        }
        var existing = instructions.findByProposalIdForUpdate(id);
        if (!existing.isEmpty()) {
            if (existing.size() != actions.size() || existing.stream().anyMatch(item -> !java.util.Objects.equals(item.replacedInstructionId(), replaceInstructionId)))
                throw new AdvisoryConflictException("Bunching recommendation was already issued with a different instruction set");
            return existing;
        }
        if (replaceInstructionId != null) {
            var predecessor = instructions.findForUpdate(replaceInstructionId).orElseThrow(AdvisoryNotFoundException::new);
            boolean busMatches = fleet.items().stream().anyMatch(b -> b.serviceId().equals(lockedRecord.bunchingEvent().serviceId())
                    && b.directionId() == lockedRecord.bunchingEvent().directionId()
                    && b.busId().equals(predecessor.busId())
                    && actions.stream().anyMatch(a -> b.busId().equals(a.busReference()) || b.registrationNumber().equals(a.busReference())));
            if (!predecessor.demoRunId().equals(run) || !busMatches)
                throw new AdvisoryConflictException("Replacement instruction belongs to another bus or run");
            if (predecessor.type() == InstructionType.BE_CAUTIOUS || !predecessor.isCurrent(clock.instant())) {
                var winner = instructions.findByDemoRunIdAndBusIdAndStatusInOrderByIssuedAtAsc(run, predecessor.busId(), List.of("ISSUED", "ACKNOWLEDGED"))
                        .stream().filter(i -> i.type() != InstructionType.BE_CAUTIOUS && !i.id().equals(replaceInstructionId))
                        .findFirst();
                if (winner.isPresent()) throw new AdvisoryConflictException("Active movement instruction " + winner.get().id() + " won the replacement");
                throw new AdvisoryConflictException("Replacement instruction is not an active movement instruction");
            }
            predecessor.cancel("CONTROLLER_REPLACED");
            currentInstructions.recordTransition(predecessor, "INSTRUCTION_REPLACED", actor);
            instructions.flush();
        }
        var issued = new java.util.ArrayList<IssuedInstruction>();
        for (int index = 0; index < actions.size(); index++) {
            var action = actions.get(index);
            var bus = currentBuses.get(action.busReference());
            if (bus == null || bus.captainUserId() == null || bus.progress() == null)
                throw new AdvisoryConflictException("Unique current captain assignment and progress required for " + action.busReference());
            var stops = context.route(bus.serviceId()).operators().stream().flatMap(o -> o.directions().stream())
                    .filter(d -> d.directionId() == bus.directionId()).flatMap(d -> d.stops().stream())
                    .filter(s -> s.stopCode().equals(lockedRecommendation.controlStopCode()) && s.stopSequence() >= bus.progress().stopSequence())
                    .toList();
            if (stops.size() != 1) throw new AdvisoryConflictException("Upcoming control stop unavailable or ambiguous for " + action.busReference());
            var stop = stops.getFirst();
            for (var active : currentInstructions.activeForBusAfterGuard(run, bus))
                if (active.type() != InstructionType.BE_CAUTIOUS && !active.id().equals(replaceInstructionId))
                    throw new AdvisoryConflictException("Active movement instruction " + active.id() + " must be replaced first");
            var instructionType = InstructionType.valueOf(action.type().name());
            var parameters = new InstructionParameters(stop.stopCode(), stop.description(), stop.stopSequence(), null, null, action.seconds());
            String message = instructionType == InstructionType.HOLD ? "Hold at " + stop.description() + " for " + action.seconds() + " sec."
                    : "Extend dwell at " + stop.description() + " by " + action.seconds() + " sec.";
            var instructionId = index == 0 ? id : UUID.randomUUID();
            issued.add(instructions.saveAndFlush(new IssuedInstruction(instructionId, id, replaceInstructionId,
                    hazard.disruptionId(), run, bus, new AdvisoryCandidate(instructionType, parameters, candidate.rationale()),
                    message, "SIMULATION", actor, clock.instant(), context.limits().acknowledgementWindow())));
            currentInstructions.recordTransition(issued.getLast(), "INSTRUCTION_ISSUED", actor);
        }
        return List.copyOf(issued);
    }

    private List<IssuedInstruction> replayRecordedDecision(RecommendationRecord record, RecommendationDecisionAction action,
            List<ReplacementBusInstruction> requested, String rationale, UUID replaceInstructionId) {
        var previous = decisions.findDecision(record.recommendation().recommendationId()).orElse(null);
        if (previous == null) return null;
        var actions = requested.stream().sorted(java.util.Comparator.comparing(
                item -> !item.busReference().equals(record.recommendation().targetBusReference()))).toList();
        var expected = action == RecommendationDecisionAction.APPROVED ? List.<ControllerBusInstruction>of()
                : actions.stream().map(item -> new ControllerBusInstruction(item.busReference(),
                        item.type() == MitigationType.HOLD ? "HOLD FOR " + item.seconds() + "s at next stop"
                                : "EXTEND DWELL BY " + item.seconds() + "s at stop " + record.recommendation().controlStopCode())).toList();
        boolean same = previous.action() == action && previous.overrideInstructions().equals(expected)
                && java.util.Objects.equals(previous.controllerRationale(), rationale == null ? null : rationale.trim());
        if (!same) throw new AdvisoryConflictException("Recommendation already has a different decision");
        var delivered = instructions.findByProposalIdOrderByIssuedAtAsc(record.recommendation().recommendationId());
        if (delivered.isEmpty()) throw new AdvisoryConflictException("Recorded recommendation decision has no instruction delivery");
        if (!java.util.Objects.equals(delivered.getFirst().replacedInstructionId(), replaceInstructionId))
            throw new AdvisoryConflictException("Recommendation was issued with a different predecessor");
        return delivered;
    }

    private static Map<String, AdvisoryContextPort.Bus> resolveBuses(RecommendationRecord record,
            List<ReplacementBusInstruction> actions, AdvisoryContextPort.Fleet fleet) {
        Map<String, AdvisoryContextPort.Bus> resolved = new java.util.LinkedHashMap<>();
        java.util.Set<String> canonical = new java.util.HashSet<>();
        for (var action : actions) {
            var buses = fleet.items().stream().filter(bus -> bus.serviceId().equals(record.bunchingEvent().serviceId())
                    && bus.directionId() == record.bunchingEvent().directionId()
                    && (bus.busId().equals(action.busReference()) || bus.registrationNumber().equals(action.busReference())))
                    .toList();
            if (buses.size() != 1 || !canonical.add(buses.getFirst().busId()))
                throw new AdvisoryConflictException("Bus references must resolve to distinct current buses");
            resolved.put(action.busReference(), buses.getFirst());
        }
        return Map.copyOf(resolved);
    }

    public record ReplacementBusInstruction(String busReference, MitigationType type, int seconds) {}
}
