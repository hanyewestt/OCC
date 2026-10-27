package com.csd.occ.planning.advisory.domain;

import com.csd.occ.planning.diagnosis.domain.PlanningScopePort;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InstructionService {
    private final AdvisoryProposalRepository proposals;
    private final IssuedInstructionRepository instructions;
    private final AdvisoryContextPort context;
    private final PlanningScopePort scopes;
    private final AdvisoryModelEvidenceRepository evidence;
    private final Clock clock;
    private final InstructionPolicy policy = new InstructionPolicy();
    public InstructionService(AdvisoryProposalRepository proposals, IssuedInstructionRepository instructions,
            AdvisoryContextPort context, PlanningScopePort scopes, AdvisoryModelEvidenceRepository evidence, Clock clock) {
        this.proposals = proposals; this.instructions = instructions; this.context = context; this.scopes = scopes;
        this.evidence = evidence; this.clock = clock;
    }
    public Page<AdvisoryProposal> proposals(int page, int size) {
        return proposals(page, size, false);
    }
    public Page<AdvisoryProposal> proposals(int page, int size, boolean pendingOnly) {
        validatePage(page, size);
        var scope = scopes.current();
        if (scope.demoRunId() == null) return Page.empty(PageRequest.of(page, size));
        var request = PageRequest.of(page, size);
        return pendingOnly
                ? proposals.findByDemoRunIdAndStatusOrderByCreatedAtAsc(scope.demoRunId(), "PENDING", request)
                : proposals.findByDemoRunIdOrderByCreatedAtAsc(scope.demoRunId(), request);
    }
    @Transactional
    public String preview(UUID id, AdvisoryCandidate override) {
        var proposal = proposals.findById(id).orElseThrow(AdvisoryNotFoundException::new);
        if (!proposal.demoRunId().equals(scopes.current().demoRunId()))
            throw new AdvisoryConflictException("Proposal is outside the active simulation");
        var hazard = context.hazard(proposal.disruptionId());
        if (!"ACTIVE".equals(hazard.status()) || !proposal.context().hasSameEvidenceAs(hazard))
            throw new AdvisoryConflictException("Hazard evidence changed; review a current proposal");
        return policy.message(override == null ? proposal.candidate() : override,
                assignedBus(proposal.demoRunId(), proposal.busId()), hazard.exposures(), hazard.facts().instruction(), context.limits());
    }
    @Transactional
    public IssuedInstruction decide(UUID id, AdvisoryCandidate override, String rationale, String actor, UUID replaceInstructionId) {
        var proposal = proposals.findForUpdate(id).orElseThrow(AdvisoryNotFoundException::new);
        var chosen = override == null ? proposal.candidate() : override;
        if (override != null && (rationale == null || rationale.isBlank())) throw new IllegalArgumentException("Replacement rationale required");
        if (!"PENDING".equals(proposal.status())) {
            if (chosen.equals(proposal.finalCandidate()) && Objects.equals(rationale, proposal.controllerRationale())
                    && Objects.equals(replaceInstructionId, proposal.replacedInstructionId()))
                return instructions.findById(id).orElseThrow(AdvisoryNotFoundException::new);
            throw new AdvisoryConflictException("Proposal already has a different decision");
        }
        UUID runId = scopes.current().demoRunId();
        if (!proposal.demoRunId().equals(runId)) throw new AdvisoryConflictException("Proposal is outside the active simulation");
        var bus = assignedBus(runId, proposal.busId());
        if (!bus.serviceId().equals(proposal.bus().serviceId()) || bus.directionId() != proposal.bus().directionId())
            throw new AdvisoryConflictException("Bus route changed; review a current advisory for its service and direction");
        var hazard = context.hazard(proposal.disruptionId());
        if (!hazard.status().equals("ACTIVE") || !proposal.context().hasSameEvidenceAs(hazard))
            throw new AdvisoryConflictException("Hazard evidence changed or is no longer active; review a current proposal");
        String message = policy.message(chosen, bus, hazard.exposures(), hazard.facts().instruction(), context.limits());
        if (replaceInstructionId != null) {
            var previous = instructions.findForUpdate(replaceInstructionId).orElseThrow(AdvisoryNotFoundException::new);
            if (!previous.demoRunId().equals(runId) || !previous.busId().equals(bus.busId()))
                throw new AdvisoryConflictException("Replacement instruction belongs to another bus or run");
        }
        for (var current : instructions.findByDemoRunIdAndBusIdAndStatusInOrderByIssuedAtAsc(runId, bus.busId(), List.of("ISSUED","ACKNOWLEDGED"))) {
            refresh(current, bus);
            if (!current.isCurrent(clock.instant())) continue;
            if (replaceInstructionId != null && current.id().equals(replaceInstructionId)) current.cancel("CONTROLLER_REPLACED");
            else if (current.type() != InstructionType.BE_CAUTIOUS && chosen.type() != InstructionType.BE_CAUTIOUS)
                throw new AdvisoryConflictException("Active movement instruction " + current.id() + " must be explicitly replaced");
        }
        instructions.flush();
        var issued = new IssuedInstruction(id, proposal.disruptionId(), runId, bus, chosen, message,
                hazard.classification(), actor, clock.instant(), context.limits().acknowledgementWindow());
        var decidedAt = clock.instant();
        proposal.decide(chosen, actor, rationale, replaceInstructionId, decidedAt);
        evidence.findById(proposal.id()).orElseGet(() -> evidence.save(new AdvisoryModelEvidence(proposal)))
                .decide(chosen, actor, rationale, replaceInstructionId, decidedAt);
        return instructions.saveAndFlush(issued);
    }
    @Transactional
    public List<IssuedInstruction> captain(String subject) {
        UUID runId = scopes.current().demoRunId();
        if (runId == null) return List.of();
        var fleet = context.fleet(runId);
        if (!List.of("RUNNING", "REPLAY_COMPLETE").contains(fleet.status())) return List.of();
        var assigned = fleet.items().stream().filter(bus -> bus.captainUserId() != null && bus.captainUserId().toString().equals(subject)).findFirst();
        if (assigned.isEmpty()) return List.of();
        var bus = assigned.get();
        var found = instructions.findByDemoRunIdAndBusIdAndStatusInOrderByIssuedAtAsc(runId, bus.busId(), List.of("ISSUED","ACKNOWLEDGED"));
        found.forEach(instruction -> refresh(instruction, bus));
        return found.stream().filter(i -> i.captainUserId().toString().equals(subject) && i.isCurrent(clock.instant())).toList();
    }
    @Transactional
    public IssuedInstruction acknowledge(UUID id, String subject) {
        var instruction = instructions.findForUpdate(id).orElseThrow(AdvisoryNotFoundException::new);
        if (!instruction.captainUserId().toString().equals(subject)) throw new AdvisoryNotFoundException();
        if (instruction.acknowledgedAt() != null) return instruction;
        if (!instruction.demoRunId().equals(scopes.current().demoRunId())) throw new AdvisoryConflictException("Simulation is no longer active");
        var bus = assignedBus(instruction.demoRunId(), instruction.busId());
        if (!bus.captainUserId().toString().equals(subject)) throw new AdvisoryNotFoundException();
        refresh(instruction, bus);
        instruction.acknowledge(subject, clock.instant());
        return instruction;
    }
    @Transactional
    public IssuedInstruction cancel(UUID id, String actor) {
        var instruction = instructions.findForUpdate(id).orElseThrow(AdvisoryNotFoundException::new);
        instruction.cancel("CONTROLLER_CANCELLED"); return instruction;
    }
    @Transactional
    public Page<IssuedInstruction> history(int page, int size) {
        validatePage(page, size);
        UUID run = scopes.current().demoRunId();
        if (run == null) return Page.empty(PageRequest.of(page, size));
        var found = instructions.findByDemoRunIdOrderByIssuedAtDesc(run, PageRequest.of(page, size));
        var fleet = context.fleet(run);
        for (var instruction : found) {
            if (!List.of("ISSUED", "ACKNOWLEDGED").contains(instruction.status())) continue;
            var bus = fleet.items().stream().filter(b -> b.busId().equals(instruction.busId())).findFirst();
            if (!List.of("RUNNING", "REPLAY_COMPLETE").contains(fleet.status()) || bus.isEmpty()) instruction.cancel("RUN_CLOSED");
            else refresh(instruction, bus.get());
        }
        return found;
    }
    private AdvisoryContextPort.Bus assignedBus(UUID runId, String busId) {
        var fleet = context.fleet(runId);
        if (!List.of("RUNNING", "REPLAY_COMPLETE").contains(fleet.status())) throw new AdvisoryConflictException("Run is closed");
        return fleet.items().stream().filter(bus -> bus.busId().equals(busId) && bus.captainUserId() != null)
                .findFirst().orElseThrow(() -> new AdvisoryConflictException("Captain assignment unavailable"));
    }
    private void refresh(IssuedInstruction instruction, AdvisoryContextPort.Bus bus) {
        if (!instruction.isCurrent(clock.instant())) { instruction.expire("VALIDITY_EXPIRED"); return; }
        var hazard = context.hazard(instruction.disruptionId());
        if ("CLEARED".equals(hazard.status())) { instruction.cancel("SOURCE_WITHDRAWN"); return; }
        var p = instruction.parameters();
        if (p.stopSequence() != null && bus.progress() != null
                && bus.progress().stopSequence() > p.stopSequence() + (p.subsequentStopCount() == null ? 0 : p.subsequentStopCount()))
            instruction.expire("STOP_RANGE_PASSED");
    }
    private void validatePage(int page, int size) { if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid page or size"); }
}
