package com.csd.occ.bunching.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class LiveBunchingAlertService {
    private final AlertEventRepository alerts;

    public LiveBunchingAlertService(AlertEventRepository alerts) { this.alerts = alerts; }

    public Page<AlertEvent> currentOpen(int page, int size) {
        return alerts.findCurrentLiveOpenAlerts(PageRequest.of(page, size));
    }
}
