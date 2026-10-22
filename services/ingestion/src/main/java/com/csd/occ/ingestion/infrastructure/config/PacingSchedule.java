package com.csd.occ.ingestion.infrastructure.config;

import com.csd.occ.ingestion.domain.PacingService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Runs pacing on its own thread instead of the shared {@code @Scheduled} thread: a slow DataMall poll
 * on that thread delayed pacing and then fired catch-up calculations back to back, so captains
 * would not receive a steady update every interval.
 */
@Component
public class PacingSchedule implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(PacingSchedule.class);

    private final PacingService pacing;
    private final PacingProperties properties;
    private ScheduledExecutorService executor;

    public PacingSchedule(PacingService pacing, PacingProperties properties) {
        this.pacing = pacing;
        this.properties = properties;
    }

    @Override
    public synchronized void start() {
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "pacing-schedule");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleAtFixedRate(this::refresh, 0, properties.interval().toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (executor == null) return;
        executor.shutdownNow();
        executor = null;
    }

    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }

    void refresh() {
        try {
            pacing.refresh();
        } catch (RuntimeException exception) {
            // Keep the schedule alive. Subscribers see calculatedAt stop advancing until the next success.
            log.warn("Pacing calculation failed: {}", exception.getMessage());
        }
    }
}
