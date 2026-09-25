package com.csd.occ.operations.infrastructure.config;

import com.csd.occ.operations.domain.DemoFleetPagePolicy;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OperationsConfig {
    @Bean
    Clock clock() { return Clock.systemUTC(); }

    @Bean
    DemoFleetPagePolicy demoFleetPagePolicy(DemoFleetProperties properties) {
        return new DemoFleetPagePolicy(properties.defaultPageSize(), properties.maxPageSize());
    }
}
