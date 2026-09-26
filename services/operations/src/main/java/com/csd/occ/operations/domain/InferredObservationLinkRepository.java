package com.csd.occ.operations.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InferredObservationLinkRepository extends JpaRepository<InferredObservationLink, UUID> {
    List<InferredObservationLink> findByObservationIdIn(Collection<UUID> ids);
    List<InferredObservationLink> findByTrackId(UUID trackId);
    long deleteByFetchedAtBefore(Instant cutoff);
}
