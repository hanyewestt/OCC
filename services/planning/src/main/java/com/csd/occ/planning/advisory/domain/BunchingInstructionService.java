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
    public IssuedInstruction approveAndIssue(UUID id, String actor) {
        var record = recommendations.findByIdempotencyKey(id).orElseThrow(AdvisoryNotFoundException::new);
        var recommendation = record.recommendation();
        if (record.classification() != SourceClassification.SIMULATION || recommendation.status() != RecommendationStatus.RECOMMENDED
                || !List.of(MitigationType.HOLD, MitigationType.DWELL_ADJUSTMENT).contains(recommendation.mitigationType()))
            throw new AdvisoryConflictException("Only validated synthetic HOLD/DWELL recommendations can be issued");
        var candidate = new MitigationCandidate(CandidateMitigationType.valueOf(recommendation.mitigationType().name()),
                recommendation.targetBusReference(), recommendation.controlStopCode(), recommendation.adjustmentSeconds(), "Controller approved validated recommendation");
        var validation = new BunchingMitigationCandidateValidator().validate(new MitigationCandidateRequest(record.classification(),
                record.bunchingEvent(), record.diagnosis(), record.mitigationContext()), candidate);
        if (!(validation instanceof MitigationCandidateValidationResult.Accepted))
            throw new AdvisoryConflictException("Stored recommendation no longer satisfies HOLD/DWELL safety rules");
        // The decision and instruction share this transaction; replay never creates another instruction.
        decisions.decide(id, RecommendationDecisionAction.APPROVED, actor, List.of(), null);
        var existing = instructions.findForUpdate(id);
        if (existing.isPresent()) return existing.get();
        var run = scopes.current().demoRunId();
        if (run == null) throw new AdvisoryConflictException("No active synthetic run");
        var hazard = context.hazard(record.bunchingEvent().bunchingEventId());
        if (!"ACTIVE".equals(hazard.status()) || !run.equals(hazard.demoRunId()))
            throw new AdvisoryConflictException("Bunching episode is no longer active in this run");
        var fleet = context.fleet(run);
        if (!List.of("RUNNING", "REPLAY_COMPLETE").contains(fleet.status())) throw new AdvisoryConflictException("Run is closed");
        var buses = fleet.items().stream().filter(b -> b.serviceId().equals(record.bunchingEvent().serviceId())
                && b.directionId() == record.bunchingEvent().directionId()
                && (b.busId().equals(recommendation.targetBusReference()) || b.registrationNumber().equals(recommendation.targetBusReference())))
                .toList();
        if (buses.size() != 1 || buses.getFirst().captainUserId() == null || buses.getFirst().progress() == null)
            throw new AdvisoryConflictException("Unique current captain assignment and progress required");
        var bus = buses.getFirst();
        var stops = context.route(bus.serviceId()).operators().stream().flatMap(o -> o.directions().stream())
                .filter(d -> d.directionId() == bus.directionId()).flatMap(d -> d.stops().stream())
                .filter(s -> s.stopCode().equals(recommendation.controlStopCode()) && s.stopSequence() >= bus.progress().stopSequence()).toList();
        if (stops.size() != 1) throw new AdvisoryConflictException("Control stop occurrence is unavailable, ambiguous or already passed");
        var stop = stops.getFirst();
        // Refresh the same recipient's existing records before checking the shared movement constraint.
        if (currentInstructions.captain(bus.captainUserId().toString()).stream().anyMatch(i -> i.type() != InstructionType.BE_CAUTIOUS))
            throw new AdvisoryConflictException("An active movement instruction must be cancelled or replaced first");
        var type = InstructionType.valueOf(recommendation.mitigationType().name());
        var parameters = new InstructionParameters(stop.stopCode(), stop.description(), stop.stopSequence(), null, null, recommendation.adjustmentSeconds());
        String message = type == InstructionType.HOLD ? "Hold at " + stop.description() + " for " + recommendation.adjustmentSeconds() + " sec."
                : "Extend dwell at " + stop.description() + " by " + recommendation.adjustmentSeconds() + " sec.";
        return instructions.saveAndFlush(new IssuedInstruction(id, hazard.disruptionId(), run, bus,
                new AdvisoryCandidate(type, parameters, candidate.rationale()), message, "SIMULATION", actor, clock.instant(), context.limits().acknowledgementWindow()));
    }
}
