package com.csd.occ.bunching.infrastructure.config;

import com.csd.occ.bunching.domain.BunchingPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class BunchingConfig {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    BunchingPolicy bunchingPolicy() {
        return new BunchingPolicy();
    }
}
