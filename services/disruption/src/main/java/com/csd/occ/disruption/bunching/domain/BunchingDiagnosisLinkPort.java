package com.csd.occ.disruption.bunching.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public interface BunchingDiagnosisLinkPort {
    Registration register(UUID eventId, Optional<BunchingDiagnosisCorrelationPolicy.Correlation> correlation,
            Instant createdAt);

    long deleteOrphanedBefore(Instant cutoff);

    record Registration(UUID canonicalEventId, boolean isRepresentative) {
        public Registration {
            Objects.requireNonNull(canonicalEventId);
        }
    }
}
