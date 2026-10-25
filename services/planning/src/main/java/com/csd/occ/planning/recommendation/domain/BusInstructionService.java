package com.csd.occ.planning.recommendation.domain;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BusInstructionService {
    private final BusInstructionPort instructions;
    private final Clock clock;

    public BusInstructionService(BusInstructionPort instructions, Clock clock) {
        this.instructions = instructions;
        this.clock = clock;
    }

    public void issueFor(RecommendationRecord recommendation, RecommendationDecision decision) {
        instructionContents(recommendation, decision).forEach(content -> instructions.saveIfAbsent(
                BusInstruction.sent(
                        instructionId(decision.recommendationId(), content.busReference()),
                        decision.recommendationId(),
                        content.busReference(),
                        content.instruction(),
                        decision.decidedAt())));
    }

    @Transactional(readOnly = true)
    public List<BusInstruction> findForBus(String busReference, int page, int size) {
        String normalizedReference = normalizeBusReference(busReference);
        validatePage(page, size);
        return instructions.findByBusReference(normalizedReference, page * size, size);
    }

    @Transactional(readOnly = true)
    public long countForBus(String busReference) {
        return instructions.countByBusReference(normalizeBusReference(busReference));
    }

    @Transactional
    public BusInstruction acknowledge(UUID instructionId, String actorSubject) {
        Objects.requireNonNull(instructionId, "instructionId is required");
        BusInstruction stored = instructions.findById(instructionId)
                .orElseThrow(() -> new BusInstructionNotFoundException(instructionId));
        if (stored.status() == BusInstructionStatus.ACKNOWLEDGED) {
            return resolveReplay(stored, actorSubject);
        }

        BusInstruction acknowledged = stored.acknowledge(actorSubject, clock.instant());
        if (instructions.acknowledgeIfSent(acknowledged)) return acknowledged;

        BusInstruction winner = instructions.findById(instructionId)
                .orElseThrow(() -> new BusInstructionNotFoundException(instructionId));
        return resolveReplay(winner, actorSubject);
    }

    private BusInstruction resolveReplay(BusInstruction instruction, String actorSubject) {
        if (instruction.status() == BusInstructionStatus.ACKNOWLEDGED
                && instruction.acknowledgedBy().filter(actorSubject::equals).isPresent()) {
            return instruction;
        }
        throw new BusInstructionAcknowledgementConflictException(instruction.instructionId());
    }

    private List<ControllerBusInstruction> instructionContents(
            RecommendationRecord record, RecommendationDecision decision) {
        if (decision.action() == RecommendationDecisionAction.REJECTED) {
            return decision.overrideInstructions();
        }
        ValidatedMitigationRecommendation recommendation = record.recommendation();
        return List.of(new ControllerBusInstruction(
                recommendation.targetBusReference(),
                suggestedInstruction(recommendation)));
    }

    private String suggestedInstruction(ValidatedMitigationRecommendation recommendation) {
        return switch (recommendation.mitigationType()) {
            case HOLD -> "Hold for " + recommendation.adjustmentSeconds()
                    + " seconds at stop " + recommendation.controlStopCode() + ".";
            case DWELL_ADJUSTMENT -> "Adjust dwell by " + recommendation.adjustmentSeconds()
                    + " seconds at stop " + recommendation.controlStopCode() + ".";
            case STANDBY_DISPATCH -> "Dispatch standby support for "
                    + recommendation.targetBusReference() + " at stop "
                    + recommendation.controlStopCode() + ".";
        };
    }

    private UUID instructionId(UUID recommendationId, String busReference) {
        String name = recommendationId + "\u0000" + busReference;
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    private String normalizeBusReference(String busReference) {
        if (busReference == null || busReference.isBlank()) {
            throw new IllegalArgumentException("busReference is required");
        }
        String normalized = busReference.trim();
        if (normalized.length() > 80) {
            throw new IllegalArgumentException("busReference must be at most 80 characters");
        }
        return normalized;
    }

    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page and size are outside the supported range");
        }
    }
}
