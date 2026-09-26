package com.csd.occ.ingestion.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI ingestionOpenApi() {
        return new OpenAPI().info(new Info().title("OCC Ingestion API").version("v1")
                .description("DataMall ingestion APIs and opt-in simulation-only bunching demo APIs."));
    }

    @Bean
    GroupedOpenApi ingestionPublicApi() {
        return GroupedOpenApi.builder().group("public").pathsToMatch("/api/v1/**").build();
    }

    @Bean
    GroupedOpenApi ingestionInternalApi() {
        return GroupedOpenApi.builder().group("internal").pathsToMatch("/internal/v1/**").build();
    }
}
