package com.csd.occ.planning.recommendationengine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RecommendationEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(RecommendationEngineApplication.class, args);
    }
}
