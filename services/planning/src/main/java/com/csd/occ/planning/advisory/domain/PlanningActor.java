package com.csd.occ.planning.advisory.domain;

public record PlanningActor(String subject, Role role) {
    public PlanningActor {
        if (subject == null || subject.isBlank() || role == null) throw new IllegalArgumentException("Authenticated actor required");
    }

    public void require(Role required) {
        if (role != required) throw new AdvisoryForbiddenException();
    }

    public void requireAny(Role... allowed) {
        for (Role candidate : allowed) if (role == candidate) return;
        throw new AdvisoryForbiddenException();
    }

    public enum Role { ADMIN, CONTROLLER, CAPTAIN, SYSTEM }
}
