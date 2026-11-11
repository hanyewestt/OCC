package com.csd.occ.planning.advisory.infrastructure.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.csd.occ.planning.advisory.domain.AssignmentOperationCoordinator;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AssignmentOperationRecoveryScheduleTest {
    @Test
    void scheduledPassUsesTheConfiguredBoundedBatch() {
        var coordinator = mock(AssignmentOperationCoordinator.class);
        var schedule = new AssignmentOperationRecoverySchedule(coordinator,
                new AssignmentRecoveryProperties(Duration.ofSeconds(5), 17));

        schedule.recover();

        verify(coordinator).recoverPending(17);
    }
}
