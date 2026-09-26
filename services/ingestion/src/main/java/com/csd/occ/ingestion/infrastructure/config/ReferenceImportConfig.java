package com.csd.occ.ingestion.infrastructure.config;

import com.csd.occ.ingestion.domain.ReferenceImportService;
import com.csd.occ.ingestion.domain.ReferenceSourceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Configuration
public class ReferenceImportConfig {
    private static final Logger log = LoggerFactory.getLogger(ReferenceImportConfig.class);
    @Bean
    @Order(1)
    @ConditionalOnProperty(name = "occ.ingestion.import-reference-on-startup", havingValue = "true", matchIfMissing = true)
    CommandLineRunner importBusReferenceTopology(ReferenceImportService imports) {
        return args -> {
            try {
                imports.refresh();
            } catch (ReferenceSourceUnavailableException exception) {
                log.error("DataMall reference refresh failed; keeping the API available with any stored topology.", exception);
            }
        };
    }
}
