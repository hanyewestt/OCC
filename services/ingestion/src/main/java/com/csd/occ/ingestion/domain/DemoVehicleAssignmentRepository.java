package com.csd.occ.ingestion.domain;
import org.springframework.data.jpa.repository.JpaRepository;
public interface DemoVehicleAssignmentRepository extends JpaRepository<DemoVehicleAssignment, DemoVehicleAssignmentId> {
    long deleteByIdDemoRunId(java.util.UUID demoRunId);
}
