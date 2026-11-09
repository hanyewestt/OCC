package com.csd.occ.planning.advisory.domain;

import com.csd.occ.planning.diagnosis.domain.PlanningScopePort;
import com.csd.occ.planning.recommendation.domain.*;
import java.time.Clock;
import java.util.List;
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
    public BunchingInstructionService(RecommendationRecordPort recommendations, RecommendationDecisionService decisions,
            IssuedInstructionRepository instructions, InstructionService currentInstructions, AdvisoryContextPort context,
            PlanningScopePort scopes, Clock clock) {
        this.recommendations = recommendations; this.decisions = decisions; this.instructions = instructions;
        this.currentInstructions = currentInstructions; this.context = context; this.scopes = scopes; this.clock = clock;
    }
    @Transactional
    public IssuedInstruction approveAndIssue(UUID id, String actor) { return approveAndIssue(id, actor, null); }
    @Transactional
    public IssuedInstruction approveAndIssue(UUID id, String actor, UUID replaceInstructionId) {
        return issue(id, actor, RecommendationDecisionAction.APPROVED, null, 0, null, replaceInstructionId);
    }
    @Transactional
    public IssuedInstruction rejectAndIssue(UUID id, String actor, MitigationType type, int seconds, String rationale) {
        var record = recommendations.findByIdempotencyKey(id).orElseThrow(AdvisoryNotFoundException::new);
        var target = record.recommendation().targetBusReference();
        return rejectAndIssue(id, actor, List.of(new ReplacementBusInstruction(target, type, seconds)), rationale).getFirst();
    }
    @Transactional
    public List<IssuedInstruction> rejectAndIssue(UUID id, String actor, List<ReplacementBusInstruction> requested, String rationale) {
        var record = recommendations.findByIdempotencyKey(id).orElseThrow(AdvisoryNotFoundException::new);
        var normalized = requested.stream().map(item -> item.busReference() == null || item.busReference().isBlank()
                ? new ReplacementBusInstruction(record.recommendation().targetBusReference(), item.type(), item.seconds()) : item).toList();
        return issue(id, actor, RecommendationDecisionAction.REJECTED, normalized, rationale, null);
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
        var recommendation = record.recommendation();
        if (record.classification() != SourceClassification.SIMULATION || recommendation.status() != RecommendationStatus.RECOMMENDED
                || !List.of(MitigationType.HOLD, MitigationType.DWELL_ADJUSTMENT).contains(recommendation.mitigationType()))
            throw new AdvisoryConflictException("Only validated synthetic HOLD/DWELL recommendations can be issued");
        var target = recommendation.targetBusReference();
        var actions = decisionAction == RecommendationDecisionAction.APPROVED
                ? List.of(new ReplacementBusInstruction(target, recommendation.mitigationType(), recommendation.adjustmentSeconds()))
                : requested;
        actions = actions.stream().sorted(java.util.Comparator.comparing(action -> !action.busReference().equals(target))).toList();
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
        var primary = actions.stream().filter(action -> action.busReference().equals(target)).findFirst().orElseThrow();
        var candidate = new MitigationCandidate(CandidateMitigationType.valueOf(primary.type().name()), target,
                recommendation.controlStopCode(), primary.seconds(), rationale == null ? "Controller approved validated recommendation" : rationale);
        if (!(new BunchingMitigationCandidateValidator().validate(new MitigationCandidateRequest(record.classification(),
                record.bunchingEvent(), record.diagnosis(), record.mitigationContext()), candidate)
                instanceof MitigationCandidateValidationResult.Accepted))
            throw new AdvisoryConflictException("Stored recommendation no longer satisfies HOLD/DWELL safety rules");
        var auditInstructions = actions.stream().map(action -> new ControllerBusInstruction(action.busReference(),
                action.type() == MitigationType.HOLD ? "HOLD FOR " + action.seconds() + "s at next stop"
                        : "EXTEND DWELL BY " + action.seconds() + "s at stop " + recommendation.controlStopCode())).toList();
        decisions.decide(id, decisionAction, actor,
                decisionAction == RecommendationDecisionAction.APPROVED ? List.of() : auditInstructions, rationale);
        var existing = instructions.findByProposalIdForUpdate(id);
        if (!existing.isEmpty()) {
            if (existing.size() != actions.size() || existing.stream().anyMatch(item -> !java.util.Objects.equals(item.replacedInstructionId(), replaceInstructionId)))
                throw new AdvisoryConflictException("Bunching recommendation was already issued with a different instruction set");
            return existing;
        }
        var run = scopes.current().demoRunId();
        if (run == null) throw new AdvisoryConflictException("No active synthetic run");
        var hazard = context.hazard(record.bunchingEvent().bunchingEventId());
        if (!"ACTIVE".equals(hazard.status()) || !run.equals(hazard.demoRunId()))
            throw new AdvisoryConflictException("Bunching episode is no longer active in this run");
        var fleet = context.fleet(run);
        if (!List.of("RUNNING", "REPLAY_COMPLETE").contains(fleet.status())) throw new AdvisoryConflictException("Run is closed");
        var issued = new java.util.ArrayList<IssuedInstruction>();
        for (int index = 0; index < actions.size(); index++) {
            var action = actions.get(index);
            var buses = fleet.items().stream().filter(b -> b.serviceId().equals(record.bunchingEvent().serviceId())
                    && b.directionId() == record.bunchingEvent().directionId()
                    && (b.busId().equals(action.busReference()) || b.registrationNumber().equals(action.busReference())))
                    .toList();
            if (buses.size() != 1 || buses.getFirst().captainUserId() == null || buses.getFirst().progress() == null)
                throw new AdvisoryConflictException("Unique current captain assignment and progress required for " + action.busReference());
            var bus = buses.getFirst();
            var stops = context.route(bus.serviceId()).operators().stream().flatMap(o -> o.directions().stream())
                    .filter(d -> d.directionId() == bus.directionId()).flatMap(d -> d.stops().stream())
                    .filter(s -> s.stopCode().equals(recommendation.controlStopCode()) && s.stopSequence() >= bus.progress().stopSequence())
                    .toList();
            if (stops.size() != 1) throw new AdvisoryConflictException("Upcoming control stop unavailable or ambiguous for " + action.busReference());
            var stop = stops.getFirst();
            // Lock and cancel the named predecessor; cancellation and issue roll back together.
            if (index == 0 && replaceInstructionId != null) {
                var predecessor = instructions.findForUpdate(replaceInstructionId).orElseThrow(AdvisoryNotFoundException::new);
                if (!predecessor.demoRunId().equals(run) || !predecessor.busId().equals(bus.busId()))
                    throw new AdvisoryConflictException("Replacement instruction belongs to another bus or run");
                if (predecessor.type() == InstructionType.BE_CAUTIOUS || !predecessor.isCurrent(clock.instant())) {
                    var winner = instructions.findByDemoRunIdAndBusIdAndStatusInOrderByIssuedAtAsc(run, bus.busId(), List.of("ISSUED", "ACKNOWLEDGED"))
                            .stream().filter(i -> i.type() != InstructionType.BE_CAUTIOUS && !i.id().equals(replaceInstructionId))
                            .findFirst();
                    if (winner.isPresent()) throw new AdvisoryConflictException("Active movement instruction " + winner.get().id() + " won the replacement");
                    throw new AdvisoryConflictException("Replacement instruction is not an active movement instruction");
                }
                predecessor.cancel("CONTROLLER_REPLACED");
            }
            for (var active : currentInstructions.captain(bus.captainUserId().toString()))
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
        }
        return List.copyOf(issued);
    }

    public record ReplacementBusInstruction(String busReference, MitigationType type, int seconds) {}
}
