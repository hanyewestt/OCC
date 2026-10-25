package com.csd.occ.ingestion.infrastructure.config;

import com.csd.occ.ingestion.domain.DemoReplayConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DemoReplayConfigurationAdapter {
    @Bean
    public DemoReplayConfiguration demoReplayConfiguration(DemoProperties properties, DemoCaptainProperties captain) {
        return new DemoReplayConfiguration(properties.scenario(), properties.seed(), properties.bunchingThreshold(), captain.subject());
    }
}
