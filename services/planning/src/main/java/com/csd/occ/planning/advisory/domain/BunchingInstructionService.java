package com.csd.occ.planning.advisory.domain;

import com.csd.occ.planning.diagnosis.domain.PlanningScopePort;
import com.csd.occ.planning.recommendation.domain.*;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import com.csd.occ.planning.domain.audit.ApplicationAuditEvent;
import com.csd.occ.planning.domain.audit.ApplicationAuditEventRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Issues reviewed synthetic bunching instructions; legacy free text remains history. */
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
    /** Single-bus approval that returns its one instruction; grouped recommendations use {@link #approveSuggestedAndIssue}. */
    @Transactional
    public IssuedInstruction approveAndIssue(UUID id, PlanningActor actor, UUID replaceInstructionId) {
        actor.require(PlanningActor.Role.CONTROLLER);
        var record = recommendations.findByIdempotencyKey(id).orElseThrow(AdvisoryNotFoundException::new);
        if (record.mitigationContext().affectedBusReferences().size() > 1)
            throw new AdvisoryConflictException("Review an outcome for every affected bus");
        return issue(id, actor.subject(), RecommendationDecisionAction.APPROVED, List.of(), null, replaceInstructionId).getFirst();
    }
    /** Approves the stored outcome for every affected bus: the single recommendation, or each grouped suggestion. */
    @Transactional
    public List<IssuedInstruction> approveSuggestedAndIssue(UUID id, PlanningActor actor) {
        actor.require(PlanningActor.Role.CONTROLLER);
        return issue(id, actor.subject(), RecommendationDecisionAction.APPROVED, List.of(), null, null);
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
                ? new ReplacementBusInstruction(record.recommendation().targetBusReference(), item.type(), item.seconds(), item.noInstructionReason()) : item).toList();
        return issue(id, actor.subject(), RecommendationDecisionAction.REJECTED, normalized, rationale, null);
    }
    private List<IssuedInstruction> issue(UUID id, String actor, RecommendationDecisionAction decisionAction,
            List<ReplacementBusInstruction> requested, String rationale, UUID replaceInstructionId) {
        validateActions(requested);
        var discoveryRecord = recommendations.findByIdempotencyKey(id).orElseThrow(AdvisoryNotFoundException::new);
        // Controller replay remains available after run closure; captain APIs enforce their own ownership.
        var priorDelivery = replayRecordedDecision(discoveryRecord, decisionAction, requested, rationale, replaceInstructionId);
        if (priorDelivery != null) return priorDelivery;
        if (recommendations.findRevision(id).isPresent())
            throw new AdvisoryConflictException("Recommendation has been superseded; review its replacement");
        var run = scopes.current().demoRunId();
        if (run == null) throw new AdvisoryConflictException("No active synthetic run");
        var discoveryFleet = context.fleet(run);
        var discoveredBuses = resolveBuses(discoveryRecord, discoveryRecord.mitigationContext().affectedBusReferences().stream()
                .map(reference -> new ReplacementBusInstruction(reference, null, 0)).toList(), discoveryFleet);
        if (replaceInstructionId != null) {
            var predecessorKey = instructions.findKeyById(replaceInstructionId).orElseThrow(AdvisoryNotFoundException::new);
            if (!run.equals(predecessorKey.getDemoRunId()) || discoveredBuses.values().stream()
                    .noneMatch(bus -> bus.busId().equals(predecessorKey.getBusId())))
                throw new AdvisoryConflictException("Replacement instruction belongs to another bus or run");
        }
        guards.lockAll(discoveredBuses.values().stream()
                .map(bus -> new AssignmentBusGuards.Key(run, bus.busId())).toList());
        if (!run.equals(scopes.current().demoRunId())) throw new AdvisoryConflictException("Simulation scope changed");
        var record = recommendations.findByIdempotencyKeyForUpdate(id).orElseThrow(AdvisoryNotFoundException::new);
        if (recommendations.findRevision(id).isPresent())
            throw new AdvisoryConflictException("Recommendation has been superseded; review its replacement");
        var recommendation = record.recommendation();
        if (record.classification() != SourceClassification.SIMULATION || recommendation.status() != RecommendationStatus.RECOMMENDED
                || !List.of(MitigationType.HOLD, MitigationType.DWELL_ADJUSTMENT).contains(recommendation.mitigationType()))
            throw new AdvisoryConflictException("Only validated synthetic HOLD/DWELL recommendations can be issued");
        var target = recommendation.targetBusReference();
        var allowedReferences = record.mitigationContext().affectedBusReferences();
        var suggestions = recommendation.suggestedOutcomes();
        if (decisionAction == RecommendationDecisionAction.APPROVED && allowedReferences.size() > 1 && suggestions.isEmpty())
            throw new AdvisoryConflictException("Review an outcome for every affected bus");
        var initialActions = decisionAction != RecommendationDecisionAction.APPROVED ? requested
                : allowedReferences.size() > 1 ? suggestions.stream().map(outcome -> new ReplacementBusInstruction(
                        outcome.busReference(), outcome.mitigationType(), outcome.adjustmentSeconds(),
                        outcome.mitigationType() == null ? outcome.reason() : null)).toList()
                : List.of(new ReplacementBusInstruction(target, recommendation.mitigationType(), recommendation.adjustmentSeconds()));
        var actions = initialActions.stream().sorted(java.util.Comparator.comparing(action -> !action.busReference().equals(target))).toList();
        if (actions.isEmpty() || actions.size() > 100 || actions.stream().map(ReplacementBusInstruction::busReference).distinct().count() != actions.size())
            throw new AdvisoryConflictException("One unique instruction per affected bus is required");
        if (!new java.util.HashSet<>(actions.stream().map(ReplacementBusInstruction::busReference).toList())
                .equals(new java.util.HashSet<>(allowedReferences)))
            throw new AdvisoryConflictException("An outcome is required for every affected bus");
        boolean refreshed = record.revisionRawObservationId() != null;
        boolean grouped = allowedReferences.size() > 1;
        validateActions(actions);
        var primary = actions.getFirst();
        String instructionRationale = rationale == null ? "Controller approved validated recommendation" : rationale;
        if (!grouped && primary.type() != null && primary.type() != MitigationType.SKIP_STOP) {
            var candidate = new MitigationCandidate(CandidateMitigationType.valueOf(primary.type().name()), target,
                    recommendation.controlStopCode(), primary.seconds(), instructionRationale);
            if (!(new BunchingMitigationCandidateValidator().validate(new MitigationCandidateRequest(record.classification(),
                    record.bunchingEvent(), record.diagnosis(), record.mitigationContext()), candidate)
                    instanceof MitigationCandidateValidationResult.Accepted))
                throw new AdvisoryConflictException("Stored recommendation no longer satisfies HOLD/DWELL safety rules");
        }
        var auditInstructions = actions.stream().map(action -> new ControllerBusInstruction(action.busReference(),
                action.type() == null ? "NO INSTRUCTION: " + action.noInstructionReason().strip()
                        : action.type() == MitigationType.SKIP_STOP ? "SKIP STOP " + recommendation.controlStopCode()
                        : action.type() == MitigationType.HOLD ? "HOLD FOR " + action.seconds() + "s at next stop"
                        : "EXTEND DWELL BY " + action.seconds() + "s at stop " + recommendation.controlStopCode())).toList();
        boolean reviewed = decisions.findDecision(id).isPresent();
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
            auditEvents.save(new ApplicationAuditEvent(UUID.nameUUIDFromBytes(decisionSourceKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    clock.instant(), clock.instant(), "ADVISORIES", "RECOMMENDATION_DECIDED", decisionAction.name(),
                    actor, "CONTROLLER", "BUNCHING_RECOMMENDATION", id.toString(), scopes.current().demoRunId(),
                    target, null, record.bunchingEvent().bunchingEventId().toString(),
                    "Bunching recommendation decided", details, decisionSourceKey));
        }
        var existing = instructions.findByProposalIdForUpdate(id);
        if (!existing.isEmpty()) {
            if (existing.size() != actions.stream().filter(action -> action.type() != null).count()
                    || existing.stream().anyMatch(item -> !java.util.Objects.equals(item.replacedInstructionId(), replaceInstructionId)))
                throw new AdvisoryConflictException("Bunching recommendation was already issued with a different instruction set");
            return existing;
        }
        if (reviewed && actions.stream().allMatch(action -> action.type() == null)) return List.of();
        var hazard = context.hazard(record.bunchingEvent().bunchingEventId());
        if (!"ACTIVE".equals(hazard.status()) || !run.equals(hazard.demoRunId()))
            throw new AdvisoryConflictException("Bunching episode is no longer active in this run");
        if (allowedReferences.size() > 1 && !refreshed) {
            AdvisoryContextPort.BunchingGroup group;
            try { group = context.bunchingGroup(run, record.bunchingEvent().bunchingEventId()); }
            catch (RuntimeException unavailable) { throw new AdvisoryConflictException("Cannot revalidate the affected bus group"); }
            if (group == null || !run.equals(group.demoRunId())
                    || !record.bunchingEvent().bunchingEventId().equals(group.bunchingEventId())
                    || !record.bunchingEvent().serviceId().equals(group.serviceId())
                    || record.bunchingEvent().directionId() != group.directionId()
                    || !record.bunchingEvent().monitoredStopCode().equals(group.stopCode())
                    || !allowedReferences.equals(group.affectedBusReferences())
                    || !target.equals(group.followingBusReference()))
                throw new AdvisoryConflictException("Affected bus group changed; refresh and review it again");
        }
        var fleet = context.fleet(run);
        var currentBuses = resolveBuses(record, actions, fleet);
        if (!discoveredBuses.values().stream().map(AdvisoryContextPort.Bus::busId).sorted().toList()
                .equals(currentBuses.values().stream().map(AdvisoryContextPort.Bus::busId).sorted().toList()))
            throw new AdvisoryConflictException("Affected bus assignments changed; refresh the recommendation");
        if (!run.equals(fleet.demoRunId()) || !List.of("RUNNING", "REPLAY_COMPLETE").contains(fleet.status()))
            throw new AdvisoryConflictException("Current simulation fleet is unavailable or closed");
        if (allowedReferences.stream().anyMatch(reference -> fleet.items().stream().filter(b ->
                b.serviceId().equals(record.bunchingEvent().serviceId())
                        && b.directionId() == record.bunchingEvent().directionId()
                        && (java.util.Objects.equals(b.busId(), reference) || java.util.Objects.equals(b.registrationNumber(), reference))).count() != 1))
            throw new AdvisoryConflictException("An affected bus is unavailable or ambiguous in the current fleet");
        AdvisoryContextPort.CurrentBunchingGroup currentGroup;
        {
            try { currentGroup = context.currentBunchingGroup(run, record.bunchingEvent().bunchingEventId()); }
            catch (RuntimeException unavailable) { throw new AdvisoryConflictException("Current bunching evidence unavailable; review again when it is available"); }
            var now = clock.instant();
            if (currentGroup == null || !run.equals(currentGroup.demoRunId())
                    || !record.bunchingEvent().bunchingEventId().equals(currentGroup.bunchingEventId())
                    || !record.bunchingEvent().serviceId().equals(currentGroup.serviceId())
                    || record.bunchingEvent().directionId() != currentGroup.directionId()
                    || !record.bunchingEvent().monitoredStopCode().equals(currentGroup.stopCode())
                    || !currentGroup.isFreshAt(now) || currentGroup.affectedBusReferences() == null)
                throw new AdvisoryConflictException("Current bunching evidence unavailable or expired; review again");
            if (refreshed
                    && (!record.revisionRawObservationId().equals(currentGroup.rawObservationId())
                        || !record.mitigationContext().currentPredictionGaps().equals(currentGroup.adjacentGapSeconds())))
                throw new AdvisoryConflictException("The reviewed prediction snapshot changed; refresh the recommendation");
            var currentReferences = currentGroup.affectedBusReferences();
            if (currentReferences.size() != allowedReferences.size()
                    || currentReferences.stream().anyMatch(reference -> reference == null || reference.isBlank()
                            || fleet.items().stream().filter(b -> b.serviceId().equals(record.bunchingEvent().serviceId())
                                && b.directionId() == record.bunchingEvent().directionId()
                                && (java.util.Objects.equals(b.busId(), reference) || java.util.Objects.equals(b.registrationNumber(), reference))).count() != 1))
                throw new AdvisoryConflictException("Affected bus group changed; refresh and review it again");
            var reviewedBusIds = allowedReferences.stream().map(reference -> fleet.items().stream().filter(b ->
                    b.serviceId().equals(record.bunchingEvent().serviceId()) && b.directionId() == record.bunchingEvent().directionId()
                    && (java.util.Objects.equals(b.busId(), reference) || java.util.Objects.equals(b.registrationNumber(), reference)))
                    .findFirst().orElseThrow().busId()).toList();
            var currentBusIds = currentReferences.stream().map(reference -> fleet.items().stream().filter(b ->
                    b.serviceId().equals(record.bunchingEvent().serviceId()) && b.directionId() == record.bunchingEvent().directionId()
                    && (java.util.Objects.equals(b.busId(), reference) || java.util.Objects.equals(b.registrationNumber(), reference)))
                    .findFirst().orElseThrow().busId()).toList();
            if (!reviewedBusIds.equals(currentBusIds) || currentBusIds.stream().distinct().count() != currentBusIds.size())
                throw new AdvisoryConflictException("Affected bus group changed; refresh and review it again");
        }
        if (grouped) {
            var delays = actions.stream().collect(java.util.stream.Collectors.toMap(
                    ReplacementBusInstruction::busReference, ReplacementBusInstruction::seconds));
            for (int index = 1; index < allowedReferences.size(); index++) {
                var preceding = allowedReferences.get(index - 1);
                var following = allowedReferences.get(index);
                int precedingDelay = delays.get(preceding);
                int followingDelay = delays.get(following);
                if (precedingDelay == 0 && followingDelay == 0) continue;
                if (!currentGroup.hasReliableGap(index - 1))
                    throw new AdvisoryConflictException("The gap between " + preceding + " and " + following
                            + " needs manual review before a timed adjustment can be issued");
                if (!BunchingRecommendationPolicy.preservesGap(currentGroup.adjacentGapSeconds().get(index - 1),
                        precedingDelay, followingDelay, record.bunchingEvent().expectedHeadwaySeconds()))
                    throw new AdvisoryConflictException("The adjustments would make the gap between " + preceding
                            + " and " + following + " too short");
            }
        }
        if (replaceInstructionId != null) {
            var predecessor = instructions.findForUpdate(replaceInstructionId).orElseThrow(AdvisoryNotFoundException::new);
            boolean busMatches = fleet.items().stream().anyMatch(b -> b.serviceId().equals(record.bunchingEvent().serviceId())
                    && b.directionId() == record.bunchingEvent().directionId()
                    && b.busId().equals(predecessor.busId())
                    && actions.stream().anyMatch(a -> java.util.Objects.equals(b.busId(), a.busReference()) || java.util.Objects.equals(b.registrationNumber(), a.busReference())));
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
        var deliveries = actions.stream().filter(action -> action.type() != null).toList();
        for (int index = 0; index < deliveries.size(); index++) {
            var action = deliveries.get(index);
            var buses = fleet.items().stream().filter(b -> b.serviceId().equals(record.bunchingEvent().serviceId())
                    && b.directionId() == record.bunchingEvent().directionId()
                    && (java.util.Objects.equals(b.busId(), action.busReference()) || java.util.Objects.equals(b.registrationNumber(), action.busReference())))
                    .toList();
            if (buses.size() != 1 || buses.getFirst().captainUserId() == null
                    || !"ASSIGNED".equals(buses.getFirst().assignmentStatus()))
                throw new AdvisoryConflictException("Current captain assignment is unavailable for " + action.busReference());
            var bus = buses.getFirst();
            if (bus.progress() == null)
                throw new AdvisoryConflictException("Current bus progress is unavailable for " + action.busReference());
            var stop = context.route(bus.serviceId()).upcomingStop(bus.directionId(),
                    recommendation.controlStopCode(), bus.progress().stopSequence()).orElseThrow(() ->
                    new AdvisoryConflictException("Upcoming control stop unavailable or ambiguous for " + action.busReference()));
            var now = clock.instant();
            if (!currentGroup.isFreshAt(now))
                throw new AdvisoryConflictException("Current bunching evidence expired during issuance; refresh and review it again");
            if (grouped && action.type() == MitigationType.HOLD && !currentGroup.fetchedAt().equals(bus.progress().observedAt()))
                throw new AdvisoryConflictException("Hold requires progress from the reviewed prediction snapshot for " + action.busReference());
            if (((grouped && action.type() == MitigationType.HOLD) || action.type() == MitigationType.SKIP_STOP)
                    && !bus.progress().isApproachingAt(now))
                throw new AdvisoryConflictException((action.type() == MitigationType.SKIP_STOP
                        ? "Skip stop requires a fresh prediction for an upcoming stop for "
                        : "Hold requires a fresh prediction for an upcoming stop for ") + action.busReference());
            if (grouped && action.type() == MitigationType.DWELL_ADJUSTMENT
                    && !bus.progress().isBoardingAt(stop, currentGroup, now))
                throw new AdvisoryConflictException("Dwell adjustment requires current boarding evidence for " + action.busReference());
            if (grouped && action.type() != MitigationType.SKIP_STOP) {
                int position = allowedReferences.indexOf(action.busReference());
                var gaps = currentGroup.adjacentGapSeconds();
                if (position == 0 || gaps == null || gaps.size() != allowedReferences.size() - 1
                        || gaps.get(position - 1) == null || gaps.get(position - 1) < 0
                        || action.seconds() > record.bunchingEvent().expectedHeadwaySeconds() - gaps.get(position - 1))
                    throw new AdvisoryConflictException("Instruction exceeds the current prediction gap limit for " + action.busReference());
                if (action.type() == MitigationType.DWELL_ADJUSTMENT
                        && action.seconds() > BunchingRecommendationPolicy.SIMULATION_MAXIMUM_DWELL_SECONDS)
                    throw new AdvisoryConflictException("Dwell adjustment exceeds the simulation limit for " + action.busReference());
            }
            for (var active : currentInstructions.activeForBusAfterGuard(run, bus))
                if (active.type() != InstructionType.BE_CAUTIOUS && !active.id().equals(replaceInstructionId))
                    throw new AdvisoryConflictException("Active movement instruction " + active.id() + " must be replaced first");
            var instructionType = InstructionType.valueOf(action.type().name());
            var parameters = new InstructionParameters(stop.stopCode(), stop.description(), stop.stopSequence(), null, null,
                    instructionType == InstructionType.SKIP_STOP ? null : action.seconds());
            String message = instructionType == InstructionType.SKIP_STOP ? "Skip stop " + stop.description() + "."
                    : instructionType == InstructionType.HOLD ? "Hold at " + stop.description() + " for " + action.seconds() + " sec."
                    : "Extend dwell at " + stop.description() + " by " + action.seconds() + " sec.";
            var instructionId = index == 0 ? id : UUID.randomUUID();
            var instruction = new IssuedInstruction(instructionId, id, replaceInstructionId,
                    hazard.disruptionId(), run, bus, new AdvisoryCandidate(instructionType, parameters, instructionRationale),
                    message, "SIMULATION", actor, clock.instant(), context.limits().acknowledgementWindow());
            try {
                issued.add(instructions.saveAndFlush(instruction));
                currentInstructions.recordTransition(issued.getLast(), "INSTRUCTION_ISSUED", actor);
            } catch (RuntimeException failure) {
                throw new DeliveryException(action.busReference(), failure);
            }
        }
        return List.copyOf(issued);
    }

    private static void validateActions(List<ReplacementBusInstruction> actions) {
        if (actions.stream().anyMatch(action -> action.type() == null
                ? action.seconds() != 0 || action.noInstructionReason() == null || action.noInstructionReason().isBlank()
                    || action.noInstructionReason().strip().length() > 480
                : action.type() == MitigationType.SKIP_STOP ? action.seconds() != 0 || action.noInstructionReason() != null
                : !List.of(MitigationType.HOLD, MitigationType.DWELL_ADJUSTMENT).contains(action.type())
                    || action.seconds() < 1 || action.seconds() > 120 || action.noInstructionReason() != null))
            throw new AdvisoryConflictException("A bus instruction exceeds the validated action or duration limits");
    }

    private List<IssuedInstruction> replayRecordedDecision(RecommendationRecord record, RecommendationDecisionAction action,
            List<ReplacementBusInstruction> requested, String rationale, UUID replaceInstructionId) {
        var previous = decisions.findDecision(record.recommendation().recommendationId()).orElse(null);
        if (previous == null) return null;
        var actions = requested.stream().sorted(java.util.Comparator.comparing(
                item -> !item.busReference().equals(record.recommendation().targetBusReference()))).toList();
        var expected = action == RecommendationDecisionAction.APPROVED ? List.<ControllerBusInstruction>of()
                : actions.stream().map(item -> new ControllerBusInstruction(item.busReference(),
                        item.type() == null ? "NO INSTRUCTION: " + item.noInstructionReason().strip()
                                : item.type() == MitigationType.SKIP_STOP ? "SKIP STOP " + record.recommendation().controlStopCode()
                                : item.type() == MitigationType.HOLD ? "HOLD FOR " + item.seconds() + "s at next stop"
                                : "EXTEND DWELL BY " + item.seconds() + "s at stop " + record.recommendation().controlStopCode())).toList();
        boolean same = previous.action() == action && previous.overrideInstructions().equals(expected)
                && java.util.Objects.equals(previous.controllerRationale(), rationale == null ? null : rationale.trim());
        if (!same) throw new AdvisoryConflictException("Recommendation already has a different decision");
        var delivered = instructions.findByProposalIdOrderByIssuedAtAsc(record.recommendation().recommendationId());
        boolean noDeliveries = action == RecommendationDecisionAction.REJECTED
                ? requested.stream().allMatch(item -> item.type() == null)
                : !record.recommendation().suggestedOutcomes().isEmpty()
                    && record.recommendation().suggestedOutcomes().stream().allMatch(item -> item.mitigationType() == null);
        if (delivered.isEmpty() && noDeliveries) return List.of();
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
                throw new AdvisoryConflictException("Affected bus group changed; bus references must resolve to distinct current buses");
            resolved.put(action.busReference(), buses.getFirst());
        }
        return Map.copyOf(resolved);
    }

    public static class DeliveryException extends RuntimeException {
        public DeliveryException(String busReference, Throwable cause) {
            super("Instruction delivery failed for " + busReference + ". No bus outcomes were saved.", cause);
        }
    }

    public record ReplacementBusInstruction(String busReference, MitigationType type, int seconds, String noInstructionReason) {
        public ReplacementBusInstruction(String busReference, MitigationType type, int seconds) {
            this(busReference, type, seconds, null);
        }
    }
}
