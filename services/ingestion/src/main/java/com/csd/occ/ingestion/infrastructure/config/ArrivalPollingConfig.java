package com.csd.occ.ingestion.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ArrivalPollingConfig {
    @Bean
    Clock ingestionClock() { return Clock.systemUTC(); }
}
