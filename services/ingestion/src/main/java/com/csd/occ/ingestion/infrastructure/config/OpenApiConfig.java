package com.csd.occ.ingestion.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI ingestionOpenApi() {
        return new OpenAPI().info(new Info().title("OCC Ingestion API").version("v1")
                .description("Read-only access to normalized DataMall bus-arrival ingestion data."));
    }
}
