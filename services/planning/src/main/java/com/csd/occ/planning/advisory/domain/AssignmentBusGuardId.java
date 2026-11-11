package com.csd.occ.planning.advisory.domain;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;

@Embeddable
public class AssignmentBusGuardId implements Serializable {
    private UUID demoRunId;
    private String busId;
    protected AssignmentBusGuardId() { }
    public AssignmentBusGuardId(UUID demoRunId, String busId) { this.demoRunId = demoRunId; this.busId = busId; }
    public UUID demoRunId() { return demoRunId; }
    public String busId() { return busId; }
    @Override public boolean equals(Object other) {
        return this == other || other instanceof AssignmentBusGuardId that
                && java.util.Objects.equals(demoRunId, that.demoRunId) && java.util.Objects.equals(busId, that.busId);
    }
    @Override public int hashCode() { return java.util.Objects.hash(demoRunId, busId); }
}
