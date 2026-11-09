package com.csd.occ.planning.diagnosis.domain;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelInvocationRecordRepository extends JpaRepository<ModelInvocationRecord, UUID> {
    Page<ModelInvocationRecord> findAllByOrderByCompletedAtDesc(Pageable pageable);
}
