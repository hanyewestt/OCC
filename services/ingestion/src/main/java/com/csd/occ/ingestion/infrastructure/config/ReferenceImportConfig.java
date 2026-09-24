package com.csd.occ.ingestion.infrastructure.config;

import com.csd.occ.ingestion.domain.ReferenceImportService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Configuration
public class ReferenceImportConfig {
    @Bean
    @Order(1)
    @ConditionalOnProperty(name = "occ.ingestion.import-reference-on-startup", havingValue = "true", matchIfMissing = true)
    CommandLineRunner importBusReferenceTopology(ReferenceImportService imports) {
        return args -> imports.refresh();
    }
}
