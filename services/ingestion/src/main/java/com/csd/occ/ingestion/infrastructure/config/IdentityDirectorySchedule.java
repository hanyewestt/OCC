package com.csd.occ.ingestion.infrastructure.config;

import com.csd.occ.ingestion.domain.IdentityProviderException;
import com.csd.occ.ingestion.domain.UserDirectoryService;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class IdentityDirectorySchedule {
    private static final Logger log = LoggerFactory.getLogger(IdentityDirectorySchedule.class);
    private final IdentityProviderProperties settings;
    private final UserDirectoryService directory;
    private final TaskScheduler scheduler;
    private final Clock clock;

    public IdentityDirectorySchedule(IdentityProviderProperties settings, UserDirectoryService directory,
            TaskScheduler scheduler, Clock clock) {
        this.settings = settings; this.directory = directory; this.scheduler = scheduler; this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        // Queue the first scan after readiness; a transient provider failure never delays application startup.
        if (settings.enabled()) scheduler.schedule(this::synchronize, clock.instant());
    }

    @Scheduled(fixedDelayString = "${occ.identity.directory-interval}", initialDelayString = "${occ.identity.directory-interval}")
    public void synchronize() {
        if (!settings.enabled()) return;
        try {
            directory.synchronize();
        } catch (IdentityProviderException unavailable) {
            log.warn("Identity directory synchronization unavailable; retaining the previous complete projection.");
        } catch (RuntimeException publicationFailed) {
            log.error("Identity directory publication failed; retaining the previous complete projection.");
        }
    }
}
