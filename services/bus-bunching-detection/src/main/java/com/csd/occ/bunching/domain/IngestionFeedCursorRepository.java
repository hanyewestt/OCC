package com.csd.occ.bunching.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestionFeedCursorRepository extends JpaRepository<IngestionFeedCursor, Integer> {
}
