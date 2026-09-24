package com.csd.occ.ingestion.infrastructure.config;

import com.csd.occ.ingestion.domain.PollingConfigurationPort;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("occ.ingestion")
public record IngestionProperties(
        List<String> monitoredServices,
        int staleAfterSeconds,
        Duration retention) implements PollingConfigurationPort {
}
