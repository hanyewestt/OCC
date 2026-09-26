package com.csd.occ.ingestion.infrastructure.config;

import com.csd.occ.ingestion.domain.PollTargetService;
import com.csd.occ.ingestion.domain.ReferenceTopologyUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Clock;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Configuration
public class IngestionConfig {
    private static final Logger log = LoggerFactory.getLogger(IngestionConfig.class);
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @Order(2)
    @ConditionalOnProperty(name = "occ.ingestion.seed-targets-on-startup", havingValue = "true", matchIfMissing = true)
    CommandLineRunner seedPollTargets(PollTargetService targets) {
        return args -> {
            try {
                targets.seed();
            } catch (ReferenceTopologyUnavailableException exception) {
                log.warn("Poll-target seeding deferred until reference topology is available.");
            }
        };
    }
}
