package com.csd.occ.ingestion.infrastructure.scheduling;

import com.csd.occ.ingestion.domain.ArrivalTelemetryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.time.Clock;

@Component
@ConditionalOnProperty(name = "occ.ingestion.recovery-enabled", havingValue = "true", matchIfMissing = true)
public class PollRecoverySchedule {
    private static final Logger log = LoggerFactory.getLogger(PollRecoverySchedule.class);
    private final ArrivalTelemetryPort telemetry;
    private final Clock clock;

    public PollRecoverySchedule(ArrivalTelemetryPort telemetry, Clock clock) {
        this.telemetry = telemetry;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 10_000)
    public void recover() {
        for (var id : telemetry.expiredIds(clock.instant(), 100)) {
            try {
                if (telemetry.recoverExpired(id, clock.instant())) log.info("pollRunId={} outcome=LEASE_EXPIRED", id);
            } catch (RuntimeException failure) {
                log.error("pollRunId={} outcome=RECOVERY_PERSISTENCE_FAILED", id);
            }
        }
    }
}
