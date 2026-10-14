package com.csd.occ.disruption.bunching.domain;
import java.util.UUID;
public interface SourceRetentionPort {
    boolean sourceIsAbsent(UUID demoRunId, UUID rawObservationId);
    boolean demoRunIsAbsent(UUID demoRunId);
}
