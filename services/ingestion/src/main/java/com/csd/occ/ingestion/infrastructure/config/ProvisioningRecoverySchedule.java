package com.csd.occ.ingestion.infrastructure.config;
import com.csd.occ.ingestion.domain.ProvisioningService;
import java.time.Clock;
import org.slf4j.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component
public class ProvisioningRecoverySchedule {
    private static final Logger log=LoggerFactory.getLogger(ProvisioningRecoverySchedule.class);
    private final ProvisioningService service; private final IdentityProviderProperties settings; private final TaskScheduler scheduler; private final Clock clock;
    public ProvisioningRecoverySchedule(ProvisioningService service,IdentityProviderProperties settings,TaskScheduler scheduler,Clock clock) { this.service=service; this.settings=settings; this.scheduler=scheduler; this.clock=clock; }
    @EventListener(ApplicationReadyEvent.class) public void start() { if(settings.enabled()) scheduler.schedule(this::recover,clock.instant()); }
    @Scheduled(fixedDelayString="${occ.identity.directory-interval}",initialDelayString="${occ.identity.directory-interval}")
    public void recover() { if(settings.enabled()) try { service.reconcile(); } catch(RuntimeException failure) { log.warn("Provisioning recovery unavailable; retaining durable operation state."); } }
}
