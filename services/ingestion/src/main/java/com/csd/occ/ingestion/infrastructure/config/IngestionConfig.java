package com.csd.occ.ingestion.infrastructure.config;

import com.csd.occ.ingestion.domain.PollTargetService;
import java.time.Clock;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Configuration
public class IngestionConfig {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @Order(2)
    @ConditionalOnProperty(name = "occ.ingestion.seed-targets-on-startup", havingValue = "true", matchIfMissing = true)
    CommandLineRunner seedPollTargets(PollTargetService targets) {
        return args -> targets.seed();
    }
}
