package com.csd.occ.ingestion.domain;

import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DemoModeService {
    private final DemoRuntimeSettingRepository settings;
    private final Clock clock;
    private final ApplicationAuditEventRepository auditEvents;

    public DemoModeService(DemoRuntimeSettingRepository settings, Clock clock, ApplicationAuditEventRepository auditEvents) {
        this.settings = settings;
        this.clock = clock;
        this.auditEvents = auditEvents;
    }

    @Transactional(readOnly = true)
    public boolean enabled() {
        return settings.findById(1).orElseThrow(() -> new IllegalStateException("Demo runtime setting is missing")).enabled();
    }

    @Transactional
    public void setEnabled(boolean enabled) { setEnabled(enabled, null); }

    @Transactional
    public void setEnabled(boolean enabled, String actorSubject) {
        var setting = settings.findById(1).orElseThrow(() -> new IllegalStateException("Demo runtime setting is missing"));
        if (setting.enabled() == enabled) return;
        var now = clock.instant();
        setting.setEnabled(enabled, now);
        auditEvents.save(new ApplicationAuditEvent(java.util.UUID.randomUUID(), now, now, "OPERATIONS",
                "SIMULATION_MODE_CHANGED", "SUCCESS", "INGESTION", actorSubject,
                actorSubject == null ? "SYSTEM" : "CONTROLLER", "DEMO_MODE", "1", null, null, null, null,
                "Simulation mode changed", java.util.Map.of("enabled", enabled), "simulation-mode:" + java.util.UUID.randomUUID()));
    }
}
