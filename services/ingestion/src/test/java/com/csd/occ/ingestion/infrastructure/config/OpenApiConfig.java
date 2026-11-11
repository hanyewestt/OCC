package com.csd.occ.ingestion.infrastructure.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI ingestionOpenApi() {
        return new OpenAPI().info(new Info().title("OCC Ingestion API").version("v1")
                        .description("DataMall ingestion APIs and runtime-toggleable simulation-only bunching demo APIs. Operational reads require ADMIN or CONTROLLER; operational writes require CONTROLLER. Admin namespaces require ADMIN. Captain endpoints retain owner-only CAPTAIN access."))
                .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"))
                        .addSecuritySchemes("OCCPeerCapability", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER)
                                .name("X-OCC-Peer-Token")));
    }

    @Bean
    GroupedOpenApi ingestionPublicApi() {
        return GroupedOpenApi.builder().group("public").pathsToMatch("/api/v1/**").build();
    }

    @Bean
    GroupedOpenApi ingestionInternalApi() {
        return GroupedOpenApi.builder().group("internal").pathsToMatch("/internal/v1/**")
                .addOpenApiCustomizer(openApi -> openApi.addSecurityItem(
                        new SecurityRequirement().addList("OCCPeerCapability")))
                .build();
    }
}
