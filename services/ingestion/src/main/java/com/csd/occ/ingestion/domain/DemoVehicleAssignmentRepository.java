package com.csd.occ.ingestion.domain;
import org.springframework.data.jpa.repository.JpaRepository;
public interface DemoVehicleAssignmentRepository extends JpaRepository<DemoVehicleAssignment, DemoVehicleAssignmentId> {
    void deleteByIdDemoRunId(java.util.UUID demoRunId);
}
