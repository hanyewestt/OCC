package com.csd.occ.disruption.traffic.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class EventEvidenceId implements Serializable {
    public UUID eventId;
    public String evidenceKind;
    public UUID sourceId;

    public EventEvidenceId() { }
    public EventEvidenceId(UUID eventId, String evidenceKind, UUID sourceId) {
        this.eventId = eventId; this.evidenceKind = evidenceKind; this.sourceId = sourceId;
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof EventEvidenceId value)) return false;
        return Objects.equals(eventId, value.eventId) && Objects.equals(evidenceKind, value.evidenceKind)
                && Objects.equals(sourceId, value.sourceId);
    }
    @Override public int hashCode() { return Objects.hash(eventId, evidenceKind, sourceId); }
}
