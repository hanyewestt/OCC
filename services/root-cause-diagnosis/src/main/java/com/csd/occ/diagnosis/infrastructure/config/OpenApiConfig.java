package com.csd.occ.diagnosis.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {
    @Bean
    OpenAPI rootCauseOpenApi() {
        return new OpenAPI().info(new Info().title("OCC Root Cause Diagnosis API")
                .description("Internal event diagnosis requests and results. BUS_BUNCHING is the first supported event type; recognized event types without an adapter return 422.")
                .version("v1"));
    }

    @Bean
    GroupedOpenApi internalDiagnosisApi() {
        return GroupedOpenApi.builder().group("internal").pathsToMatch("/internal/v1/**").build();
    }
}
