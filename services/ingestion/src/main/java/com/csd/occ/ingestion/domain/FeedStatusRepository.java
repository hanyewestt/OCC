package com.csd.occ.ingestion.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FeedStatusRepository extends JpaRepository<FeedStatus, FeedStatusId> {
}
