package com.csd.occ.ingestion.domain;

import java.util.List;
import java.util.UUID;

public interface BunchingControlPort {
    void selectDemo(UUID runId);
    void selectLive();
    List<DemoBunchingAlert> alerts(UUID runId);
    LiveBunchingAlertPage currentLiveAlerts(int page, int size);
}
