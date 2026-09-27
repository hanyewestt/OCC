package com.csd.occ.planning.recommendationengine.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI recommendationEngineOpenApi() {
        return new OpenAPI().info(new Info()
                .title("OCC Recommendation Engine API")
                .version("1.0.0")
                .description("Internal API for validated simulated bus-bunching mitigation recommendations."));
    }

    @Bean
    GroupedOpenApi internalRecommendationApi() {
        return GroupedOpenApi.builder()
                .group("internal")
                .pathsToMatch("/internal/v1/**")
                .build();
    }
}
