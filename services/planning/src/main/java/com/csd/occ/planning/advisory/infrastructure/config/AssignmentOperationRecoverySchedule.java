package com.csd.occ.planning.advisory.infrastructure.config;

import com.csd.occ.planning.advisory.domain.AssignmentOperationCoordinator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AssignmentOperationRecoverySchedule {
    private final AssignmentOperationCoordinator coordinator;
    private final AssignmentRecoveryProperties properties;

    public AssignmentOperationRecoverySchedule(AssignmentOperationCoordinator coordinator,
            AssignmentRecoveryProperties properties) {
        this.coordinator = coordinator;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${occ.planning.assignment-recovery.interval:5s}", initialDelayString = "${occ.planning.assignment-recovery.interval:5s}")
    public void recover() {
        coordinator.recoverPending(properties.batchSize());
    }
}
