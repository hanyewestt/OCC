package com.csd.occ.operations.domain;

import java.util.UUID;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DemoAssignmentRepository extends JpaRepository<DemoAssignment, UUID> {
    Page<DemoAssignment> findByRunId(UUID runId, Pageable pageable);
    Optional<DemoAssignment> findByVehicleId(UUID vehicleId);
}
