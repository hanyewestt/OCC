package com.csd.occ.planning.diagnosis.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RootCauseDiagnosisRepository extends JpaRepository<RootCauseDiagnosisEntity, UUID> {
    Optional<RootCauseDiagnosisEntity> findByRequestId(UUID requestId);
}
