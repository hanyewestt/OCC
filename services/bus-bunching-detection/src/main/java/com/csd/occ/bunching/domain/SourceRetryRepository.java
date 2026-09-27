package com.csd.occ.bunching.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceRetryRepository extends JpaRepository<SourceRetry, UUID> {
    List<SourceRetry> findByNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(Instant now, Pageable pageable);
    List<SourceRetry> findByFeedScopeAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            String feedScope, Instant now, Pageable pageable);
}
