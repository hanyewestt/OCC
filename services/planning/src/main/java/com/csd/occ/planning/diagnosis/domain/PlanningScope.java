package com.csd.occ.planning.diagnosis.domain;

import java.util.Objects;
import java.util.UUID;

/** The shared consumer selection, not a separate Planning mode. */
public record PlanningScope(String mode, UUID demoRunId) {
    public PlanningScope {
        if (!("LIVE".equals(mode) && demoRunId == null)
                && !("DEMO".equals(mode) && demoRunId != null))
            throw new IllegalArgumentException("Invalid Planning data scope");
    }

    public String classification() { return "LIVE".equals(mode) ? "OBSERVATION_ONLY" : "SIMULATION"; }

    public boolean includes(BunchingEventFacts event) {
        return classification().equals(event.classification()) && Objects.equals(demoRunId, event.demoRunId());
    }

    public static boolean valid(String classification, UUID demoRunId) {
        return "OBSERVATION_ONLY".equals(classification) && demoRunId == null
                || "SIMULATION".equals(classification) && demoRunId != null;
    }
}
