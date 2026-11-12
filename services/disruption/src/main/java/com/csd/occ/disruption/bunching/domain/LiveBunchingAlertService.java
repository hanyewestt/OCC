package com.csd.occ.disruption.bunching.domain;

import java.time.Clock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class LiveBunchingAlertService {
    private final AlertEventRepository alerts;
    private final Clock clock;

    public LiveBunchingAlertService(AlertEventRepository alerts, Clock clock) {
        this.alerts = alerts;
        this.clock = clock;
    }

    public Page<AlertEvent> currentOpen(int page, int size) {
        return alerts.findCurrentLiveOpenAlerts(clock.instant(), PageRequest.of(page, size));
    }
}
