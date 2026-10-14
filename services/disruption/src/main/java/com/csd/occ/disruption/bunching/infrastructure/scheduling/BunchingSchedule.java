package com.csd.occ.disruption.bunching.infrastructure.scheduling;

import com.csd.occ.disruption.bunching.domain.BunchingConsumptionService;
import com.csd.occ.disruption.bunching.infrastructure.config.BunchingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "occ.disruption.consumer-enabled", havingValue = "true", matchIfMissing = true)
public class BunchingSchedule {
    private static final Logger log = LoggerFactory.getLogger(BunchingSchedule.class);
    private final BunchingConsumptionService consumption;
    private final BunchingProperties settings;

    public BunchingSchedule(BunchingConsumptionService consumption, BunchingProperties settings) {
        this.consumption = consumption;
        this.settings = settings;
    }

    @Scheduled(fixedDelayString = "${occ.disruption.poll-interval}")
    public void consumeStoredArrivals() {
        try {
            var result = consumption.consume(settings.batchSize(), settings.threshold(), settings.staleAfter(), settings.retryDelay());
            log.info("Bunching consumption: recorded={}, duplicates={}, rejected={}, failedObservationIds={}",
                    result.recorded(), result.duplicates(), result.rejected(), result.failedObservationIds());
        } catch (RuntimeException failure) {
            // Provider/JDBC exception text can expose sensitive configuration. No raw exception logging.
            log.error("Bunching consumption unavailable; check database connectivity and ingestion read-view grants. Will retry.");
        }
    }
}
