package com.csd.occ.ingestion.domain;

import java.util.UUID;

public final class DemoRunAlreadyActiveException extends RuntimeException {
    private final UUID activeRunId;
    public DemoRunAlreadyActiveException(UUID activeRunId) {
        super("A demo run is already active"); this.activeRunId = activeRunId;
    }
    public UUID activeRunId() { return activeRunId; }
}
