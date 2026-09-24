package com.csd.occ.ingestion.domain;

import java.time.Duration;
import java.util.List;

public interface PollingConfigurationPort {
    List<String> monitoredServices();
    int staleAfterSeconds();
    Duration retention();
}
