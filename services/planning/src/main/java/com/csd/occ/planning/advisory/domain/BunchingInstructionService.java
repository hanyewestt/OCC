package com.csd.occ.planning.advisory.domain;

import com.csd.occ.planning.diagnosis.domain.PlanningScopePort;
import com.csd.occ.planning.recommendation.domain.*;
import java.time.Clock;
import java.util.List;
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
                ? new ReplacementBusInstruction(record.recommendation().targetBusReference(), item.type(), item.seconds(), item.noInstructionReason()) : item).toList();
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
        var record = recommendations.findByIdempotencyKeyForUpdate(id).orElseThrow(AdvisoryNotFoundException::new);
        if (recommendations.findRevision(id).isPresent())
            throw new AdvisoryConflictException("Recommendation has been superseded; review its replacement");
        var recommendation = record.recommendation();
        if (record.classification() != SourceClassification.SIMULATION || recommendation.status() != RecommendationStatus.RECOMMENDED
                || !List.of(MitigationType.HOLD, MitigationType.DWELL_ADJUSTMENT).contains(recommendation.mitigationType()))
            throw new AdvisoryConflictException("Only validated synthetic HOLD/DWELL recommendations can be issued");
        var target = recommendation.targetBusReference();
        var allowedReferences = record.mitigationContext().affectedBusReferences();
        if (decisionAction == RecommendationDecisionAction.APPROVED && allowedReferences.size() > 1)
            throw new AdvisoryConflictException("Review an outcome for every affected bus");
        var initialActions = decisionAction == RecommendationDecisionAction.APPROVED
                ? List.of(new ReplacementBusInstruction(target, recommendation.mitigationType(), recommendation.adjustmentSeconds()))
                : requested;
        var actions = initialActions.stream().sorted(java.util.Comparator.comparing(action -> !action.busReference().equals(target))).toList();
        if (actions.isEmpty() || actions.size() > 100 || actions.stream().map(ReplacementBusInstruction::busReference).distinct().count() != actions.size())
            throw new AdvisoryConflictException("One unique instruction per affected bus is required");
        if (!new java.util.HashSet<>(actions.stream().map(ReplacementBusInstruction::busReference).toList())
                .equals(new java.util.HashSet<>(allowedReferences)))
            throw new AdvisoryConflictException("An outcome is required for every affected bus");
        var permittedType = record.mitigationContext().followingBusState() == FollowingBusState.READY_TO_DEPART
                ? MitigationType.HOLD : MitigationType.DWELL_ADJUSTMENT;
        int maxSeconds = Math.min(record.bunchingEvent().expectedHeadwaySeconds()
                - record.bunchingEvent().observedHeadwaySeconds().getLast(),
                permittedType == MitigationType.HOLD ? record.mitigationContext().maximumHoldSeconds()
                        : record.mitigationContext().maximumDwellAdjustmentSeconds());
        final int allowedSeconds = Math.min(maxSeconds, 120);
        if (actions.stream().anyMatch(action -> action.type() == null
                ? action.seconds() != 0 || action.noInstructionReason() == null || action.noInstructionReason().isBlank()
                    || action.noInstructionReason().strip().length() > 480
                : action.type() == MitigationType.SKIP_STOP ? action.seconds() != 0 || action.noInstructionReason() != null
                : action.type() != permittedType || action.seconds() < 1 || action.seconds() > allowedSeconds
                    || action.noInstructionReason() != null))
            throw new AdvisoryConflictException("A bus instruction exceeds the validated action or duration limits");
        var primary = actions.stream().filter(action -> action.busReference().equals(target)).findFirst().orElseThrow();
        var primaryType = primary.type() == null || primary.type() == MitigationType.SKIP_STOP ? recommendation.mitigationType() : primary.type();
        var primarySeconds = primary.type() == null || primary.type() == MitigationType.SKIP_STOP ? recommendation.adjustmentSeconds() : primary.seconds();
        var candidate = new MitigationCandidate(CandidateMitigationType.valueOf(primaryType.name()), target,
                recommendation.controlStopCode(), primarySeconds, rationale == null ? "Controller approved validated recommendation" : rationale);
        if (!(new BunchingMitigationCandidateValidator().validate(new MitigationCandidateRequest(record.classification(),
                record.bunchingEvent(), record.diagnosis(), record.mitigationContext()), candidate)
                instanceof MitigationCandidateValidationResult.Accepted))
            throw new AdvisoryConflictException("Stored recommendation no longer satisfies HOLD/DWELL safety rules");
        var auditInstructions = actions.stream().map(action -> new ControllerBusInstruction(action.busReference(),
                action.type() == null ? "NO INSTRUCTION: " + action.noInstructionReason().strip()
                        : action.type() == MitigationType.SKIP_STOP ? "SKIP STOP " + recommendation.controlStopCode()
                        : action.type() == MitigationType.HOLD ? "HOLD FOR " + action.seconds() + "s at next stop"
                        : "EXTEND DWELL BY " + action.seconds() + "s at stop " + recommendation.controlStopCode())).toList();
        decisions.decide(id, decisionAction, actor,
                decisionAction == RecommendationDecisionAction.APPROVED ? List.of() : auditInstructions, rationale);
        var existing = instructions.findByProposalIdForUpdate(id);
        if (!existing.isEmpty()) {
            if (existing.size() != actions.stream().filter(action -> action.type() != null).count()
                    || existing.stream().anyMatch(item -> !java.util.Objects.equals(item.replacedInstructionId(), replaceInstructionId)))
                throw new AdvisoryConflictException("Bunching recommendation was already issued with a different instruction set");
            return existing;
        }
        var run = scopes.current().demoRunId();
        if (run == null) throw new AdvisoryConflictException("No active synthetic run");
        var hazard = context.hazard(record.bunchingEvent().bunchingEventId());
        if (!"ACTIVE".equals(hazard.status()) || !run.equals(hazard.demoRunId()))
            throw new AdvisoryConflictException("Bunching episode is no longer active in this run");
        if (allowedReferences.size() > 1) {
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
        if (!List.of("RUNNING", "REPLAY_COMPLETE").contains(fleet.status())) throw new AdvisoryConflictException("Run is closed");
        if (allowedReferences.stream().anyMatch(reference -> fleet.items().stream().filter(b ->
                b.serviceId().equals(record.bunchingEvent().serviceId())
                        && b.directionId() == record.bunchingEvent().directionId()
                        && (java.util.Objects.equals(b.busId(), reference) || java.util.Objects.equals(b.registrationNumber(), reference))).count() != 1))
            throw new AdvisoryConflictException("An affected bus is unavailable or ambiguous in the current fleet");
        {
            AdvisoryContextPort.CurrentBunchingGroup currentGroup;
            try { currentGroup = context.currentBunchingGroup(run, record.bunchingEvent().bunchingEventId()); }
            catch (RuntimeException unavailable) { throw new AdvisoryConflictException("Current bunching evidence unavailable; review again when it is available"); }
            var now = clock.instant();
            if (currentGroup == null || !run.equals(currentGroup.demoRunId())
                    || !record.bunchingEvent().bunchingEventId().equals(currentGroup.bunchingEventId())
                    || !record.bunchingEvent().serviceId().equals(currentGroup.serviceId())
                    || record.bunchingEvent().directionId() != currentGroup.directionId()
                    || !record.bunchingEvent().monitoredStopCode().equals(currentGroup.stopCode())
                    || currentGroup.rawObservationId() == null || currentGroup.fetchedAt() == null
                    || currentGroup.validUntil() == null || currentGroup.fetchedAt().isAfter(now)
                    || !now.isBefore(currentGroup.validUntil()) || currentGroup.affectedBusReferences() == null)
                throw new AdvisoryConflictException("Current bunching evidence unavailable or expired; review again");
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
            if (buses.size() != 1 || buses.getFirst().captainUserId() == null || buses.getFirst().progress() == null)
                throw new AdvisoryConflictException("Unique current captain assignment and progress required for " + action.busReference());
            var bus = buses.getFirst();
            var stops = context.route(bus.serviceId()).operators().stream().flatMap(o -> o.directions().stream())
                    .filter(d -> d.directionId() == bus.directionId()).flatMap(d -> d.stops().stream())
                    .filter(s -> s.stopCode().equals(recommendation.controlStopCode()) && s.stopSequence() >= bus.progress().stopSequence())
                    .toList();
            if (stops.size() != 1) throw new AdvisoryConflictException("Upcoming control stop unavailable or ambiguous for " + action.busReference());
            var stop = stops.getFirst();
            if (action.type() == MitigationType.SKIP_STOP && (bus.progress().estimatedArrivalAt() == null
                    || bus.progress().freshUntil() == null || bus.progress().observedAt() == null
                    || bus.progress().observedAt().isAfter(clock.instant())
                    || !clock.instant().isBefore(bus.progress().freshUntil())
                    || !clock.instant().isBefore(bus.progress().estimatedArrivalAt())))
                throw new AdvisoryConflictException("Skip stop requires a fresh prediction for an upcoming stop for " + action.busReference());
            for (var active : currentInstructions.captain(bus.captainUserId().toString()))
                if (active.type() != InstructionType.BE_CAUTIOUS && !active.id().equals(replaceInstructionId))
                    throw new AdvisoryConflictException("Active movement instruction " + active.id() + " must be replaced first");
            var instructionType = InstructionType.valueOf(action.type().name());
            var parameters = new InstructionParameters(stop.stopCode(), stop.description(), stop.stopSequence(), null, null,
                    instructionType == InstructionType.SKIP_STOP ? null : action.seconds());
            String message = instructionType == InstructionType.SKIP_STOP ? "Skip stop " + stop.description() + "."
                    : instructionType == InstructionType.HOLD ? "Hold at " + stop.description() + " for " + action.seconds() + " sec."
                    : "Extend dwell at " + stop.description() + " by " + action.seconds() + " sec.";
            var instructionId = index == 0 ? id : UUID.randomUUID();
            issued.add(instructions.saveAndFlush(new IssuedInstruction(instructionId, id, replaceInstructionId,
                    hazard.disruptionId(), run, bus, new AdvisoryCandidate(instructionType, parameters, candidate.rationale()),
                    message, "SIMULATION", actor, clock.instant(), context.limits().acknowledgementWindow())));
        }
        return List.copyOf(issued);
    }

    public record ReplacementBusInstruction(String busReference, MitigationType type, int seconds, String noInstructionReason) {
        public ReplacementBusInstruction(String busReference, MitigationType type, int seconds) {
            this(busReference, type, seconds, null);
        }
    }
}
