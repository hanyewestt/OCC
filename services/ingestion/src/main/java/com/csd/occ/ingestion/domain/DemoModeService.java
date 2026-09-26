package com.csd.occ.ingestion.domain;

import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DemoModeService {
    private final DemoRuntimeSettingRepository settings;
    private final Clock clock;

    public DemoModeService(DemoRuntimeSettingRepository settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public boolean enabled() {
        return settings.findById(1).orElseThrow(() -> new IllegalStateException("Demo runtime setting is missing")).enabled();
    }

    @Transactional
    public void setEnabled(boolean enabled) {
        settings.findById(1).orElseThrow(() -> new IllegalStateException("Demo runtime setting is missing"))
                .setEnabled(enabled, clock.instant());
    }
}
