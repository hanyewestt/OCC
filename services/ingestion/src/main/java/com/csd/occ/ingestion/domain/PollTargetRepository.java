package com.csd.occ.ingestion.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import com.csd.occ.ingestion.domain.PollTarget;

public interface PollTargetRepository extends JpaRepository<PollTarget, PollTargetId> {
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("delete from PollTarget target where target.enabled = false and target.serviceId is null")
    int deleteDisabledLegacyTargets();
}
