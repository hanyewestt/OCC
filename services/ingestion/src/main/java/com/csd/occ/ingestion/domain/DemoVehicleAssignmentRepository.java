package com.csd.occ.ingestion.domain;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
public interface DemoVehicleAssignmentRepository extends JpaRepository<DemoVehicleAssignment, DemoVehicleAssignmentId> {
    org.springframework.data.domain.Page<DemoVehicleAssignment> findByIdDemoRunIdOrderByBusId(
            java.util.UUID demoRunId, org.springframework.data.domain.Pageable pageable);
    long deleteByIdDemoRunId(java.util.UUID demoRunId);
    java.util.List<DemoVehicleAssignment> findByIdDemoRunIdAndCaptainUserIdIn(java.util.UUID demoRunId,
            java.util.List<java.util.UUID> captainUserIds);
    // At most one row matches: uq_demo_vehicle_assignment_user allows one bus per captain per run.
    java.util.Optional<DemoVehicleAssignment> findByIdDemoRunIdAndCaptainUserId(java.util.UUID demoRunId,
            java.util.UUID captainUserId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select assignment from DemoVehicleAssignment assignment "
            + "where assignment.id.demoRunId = :runId and assignment.busId = :busId")
    java.util.Optional<DemoVehicleAssignment> findByRunIdAndBusIdForUpdate(
            @Param("runId") java.util.UUID runId, @Param("busId") String busId);
}
