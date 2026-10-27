package com.csd.occ.planning.advisory.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdvisoryModelEvidenceRepository extends JpaRepository<AdvisoryModelEvidence, UUID> {}
