package com.csd.occ.planning.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PlanningOpenApiConfig {
    @Bean
    OpenAPI planningOpenApi() {
        return new OpenAPI().info(new Info()
                .title("OCC Planning API")
                .version("1.0.0")
                .description("Diagnosis and validated mitigation recommendations for operational disruptions."))
                .components(new Components()
                        .addSecuritySchemes("Supabase JWT", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"))
                        .addSecuritySchemes("Disruption peer token", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER)
                                .name("X-OCC-Peer-Token")));
    }

    @Bean
    GroupedOpenApi publicPlanningApi() {
        return GroupedOpenApi.builder().group("planning")
                .pathsToMatch("/api/v1/planning/**")
                .addOpenApiCustomizer(openApi -> openApi.addSecurityItem(
                        new SecurityRequirement().addList("Supabase JWT")))
                .build();
    }

    @Bean
    GroupedOpenApi internalPlanningApi() {
        return GroupedOpenApi.builder().group("planning-internal")
                .pathsToMatch("/internal/v1/planning/**")
                .addOpenApiCustomizer(openApi -> openApi.addSecurityItem(
                        new SecurityRequirement().addList("Disruption peer token")))
                .build();
    }
}
