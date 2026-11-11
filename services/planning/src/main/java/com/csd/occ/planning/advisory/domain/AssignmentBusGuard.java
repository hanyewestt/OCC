package com.csd.occ.planning.advisory.domain;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "bus_assignment_guard", schema = "planning")
public class AssignmentBusGuard {
    @EmbeddedId private AssignmentBusGuardId id;
    protected AssignmentBusGuard() { }
    public AssignmentBusGuard(UUID runId, String busId) { id = new AssignmentBusGuardId(runId, busId); }
    public UUID demoRunId() { return id.demoRunId(); }
    public String busId() { return id.busId(); }
}
