package com.csd.occ.ingestion.domain;
import org.springframework.data.jpa.repository.JpaRepository;
public interface DemoVehicleAssignmentRepository extends JpaRepository<DemoVehicleAssignment, DemoVehicleAssignmentId> {
    org.springframework.data.domain.Page<DemoVehicleAssignment> findByIdDemoRunIdOrderByBusId(
            java.util.UUID demoRunId, org.springframework.data.domain.Pageable pageable);
    long deleteByIdDemoRunId(java.util.UUID demoRunId);
    // At most one row matches: uq_demo_vehicle_assignment_user allows one bus per captain per run.
    java.util.Optional<DemoVehicleAssignment> findByIdDemoRunIdAndCaptainUserId(java.util.UUID demoRunId,
            java.util.UUID captainUserId);
}
