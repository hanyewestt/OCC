package com.csd.occ.operations.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DemoVehicleRepository extends JpaRepository<DemoVehicle, UUID> {
    List<DemoVehicle> findByRunIdOrderByCreatedAtAsc(UUID runId);
}
