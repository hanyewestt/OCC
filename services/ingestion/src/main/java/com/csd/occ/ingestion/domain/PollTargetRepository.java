package com.csd.occ.ingestion.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import com.csd.occ.ingestion.domain.PollTarget;

public interface PollTargetRepository extends JpaRepository<PollTarget, PollTargetId> {
}
