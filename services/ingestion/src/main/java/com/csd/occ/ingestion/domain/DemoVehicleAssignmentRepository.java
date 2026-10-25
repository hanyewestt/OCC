package com.csd.occ.ingestion.domain;
import org.springframework.data.jpa.repository.JpaRepository;
public interface DemoVehicleAssignmentRepository extends JpaRepository<DemoVehicleAssignment, DemoVehicleAssignmentId> {
    org.springframework.data.domain.Page<DemoVehicleAssignment> findByIdDemoRunIdOrderByBusId(
            java.util.UUID demoRunId, org.springframework.data.domain.Pageable pageable);
    long deleteByIdDemoRunId(java.util.UUID demoRunId);
}
