package com.csd.occ.planning.diagnosis.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelInvocationRecordRepository extends JpaRepository<ModelInvocationRecord, UUID> { }
