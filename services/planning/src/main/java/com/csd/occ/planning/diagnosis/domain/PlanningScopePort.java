package com.csd.occ.planning.diagnosis.domain;

public interface PlanningScopePort {
    PlanningScope current();

    final class UnavailableException extends RuntimeException {
        public UnavailableException(Throwable cause) { super("Shared data selection is unavailable", cause); }
    }
}
