package com.csd.occ.planning.advisory.domain;

import java.util.List;

public final class InstructionPolicy {
    public String message(AdvisoryCandidate candidate, AdvisoryContextPort.Bus bus,
            List<AdvisoryContextPort.Exposure> exposure, String sourceInstruction, AdvisoryContextPort.Limits limits) {
        if (candidate == null || candidate.type() == null || candidate.parameters() == null
                || candidate.rationale() == null || candidate.rationale().isBlank())
            throw new IllegalArgumentException("Instruction type, parameters and rationale required");
        var p = candidate.parameters();
        if (bus.progress() == null) throw new IllegalArgumentException("Synthetic progress unavailable");
        if (exposure.stream().noneMatch(e -> e.busId().equals(bus.busId()) && e.serviceId().equals(bus.serviceId())
                && e.directionId() == bus.directionId() && e.stopSequence() >= bus.progress().stopSequence()))
            throw new IllegalArgumentException("Bus has no current downstream exposure");
        if (candidate.type() != InstructionType.REDUCE_SPEED) {
            if (p.stopCode() == null || p.stopName() == null || p.stopSequence() == null
                    || exposure.stream().noneMatch(e -> e.busId().equals(bus.busId()) && e.stopCode().equals(p.stopCode())
                    && e.stopSequence() == p.stopSequence() && e.stopName().equals(p.stopName())))
                throw new IllegalArgumentException("Target stop occurrence is not in captured exposure");
        }
        String result = switch (candidate.type()) {
            case BE_CAUTIOUS -> "Disruption near " + p.stopName() + ". Be cautious.";
            case REDUCE_SPEED -> {
                if (p.speedReductionKmh() == null || p.speedReductionKmh() < 1 || p.speedReductionKmh() > limits.maxSpeedReductionKmh()
                        || p.durationSeconds() == null || p.durationSeconds() < 60 || p.durationSeconds() % 60 != 0
                        || p.durationSeconds() > limits.maxDurationSeconds()
                        || !bus.progress().allowedSpeedReductionsKmh().contains(p.speedReductionKmh())
                        || !bus.progress().allowedDurationsSeconds().contains(p.durationSeconds()))
                    throw new IllegalArgumentException("Speed advice must use permitted synthetic values and limits");
                yield "Reduce speed by " + p.speedReductionKmh() + " km/h for " + p.durationSeconds() / 60 + " min.";
            }
            case SLOW_DOWN_NEAR_STOPS -> {
                if (p.subsequentStopCount() == null || p.subsequentStopCount() < 0 || p.subsequentStopCount() > limits.maxSubsequentStops())
                    throw new IllegalArgumentException("Invalid subsequent stop count");
                for (int offset = 0; offset <= p.subsequentStopCount(); offset++) {
                    int sequence = p.stopSequence() + offset;
                    if (exposure.stream().noneMatch(e -> e.busId().equals(bus.busId()) && e.stopSequence() == sequence))
                        throw new IllegalArgumentException("Instruction range exceeds captured affected stops");
                }
                yield "Slow down near " + p.stopName() + (p.subsequentStopCount() == 0 ? "."
                        : " and the next " + p.subsequentStopCount() + " stops.");
            }
            case HOLD, DWELL_ADJUSTMENT -> throw new IllegalArgumentException("Use the validated bunching recommendation for HOLD/DWELL");
        };
        if (sourceInstruction != null && !sourceInstruction.isBlank()) {
            if (candidate.type() != InstructionType.BE_CAUTIOUS)
                throw new IllegalArgumentException("Source restrictions require a caution instruction");
            result = "Disruption near " + p.stopName() + ". " + sourceInstruction.strip();
        }
        return result;
    }
}
