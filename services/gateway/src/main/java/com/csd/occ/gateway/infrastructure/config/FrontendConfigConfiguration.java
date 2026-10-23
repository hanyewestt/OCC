package com.csd.occ.gateway.infrastructure.config;

import com.csd.occ.gateway.domain.FrontendConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class FrontendConfigConfiguration {
    @Bean
    FrontendConfig frontendConfig(FrontendProperties properties) {
        return new FrontendConfig(properties.projectUrl().toString(), properties.publishableKey(),
                properties.controllerRefreshInterval().toSeconds(), properties.ingestionPollInterval().toSeconds(),
                properties.routeIncidentGapTolerance().toSeconds());
    }
}
