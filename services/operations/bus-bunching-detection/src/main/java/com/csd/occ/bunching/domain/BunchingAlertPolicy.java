package com.csd.occ.bunching.domain;

import java.util.Optional;
import java.util.UUID;

public class BunchingAlertPolicy {
    public enum Transition { OPENED, CLEARED, NONE }

    public Transition transition(BunchingEvaluation.State state, Optional<UUID> activeAlert) {
        boolean active = activeAlert.isPresent();
        return switch (state) {
            case BUNCHING -> active ? Transition.NONE : Transition.OPENED;
            case NORMAL -> active ? Transition.CLEARED : Transition.NONE;
            case INSUFFICIENT_EVIDENCE -> Transition.NONE;
        };
    }
}
