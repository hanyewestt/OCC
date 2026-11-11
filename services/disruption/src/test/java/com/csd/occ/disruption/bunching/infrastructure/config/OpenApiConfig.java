package com.csd.occ.disruption.bunching.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI bunchingOpenApi() {
        return new OpenAPI().info(new Info().title("OCC Disruption API").version("v1")
                .description("Bunching detection, traffic incident detection, and their authorized operational APIs. Operational reads require ADMIN or CONTROLLER; operational writes require CONTROLLER. Admin namespaces require ADMIN. Captain endpoints retain owner-only CAPTAIN access."));
    }

    @Bean
    GroupedOpenApi bunchingPublicApi() {
        return GroupedOpenApi.builder().group("public").pathsToMatch("/api/v1/**").build();
    }

    @Bean
    GroupedOpenApi bunchingInternalApi() {
        return GroupedOpenApi.builder().group("internal").pathsToMatch("/internal/v1/**").build();
    }
}
