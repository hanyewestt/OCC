package com.csd.occ.operations.infrastructure.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(info = @Info(title = "OCC Operations API", version = "v1",
        description = "Prototype-owned draft demo vehicles and assignments."))
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfig {
    @org.springframework.context.annotation.Bean
    org.springdoc.core.customizers.OpenApiCustomizer demoAuthenticationContract(CognitoProperties properties) {
        return document -> document.getPaths().forEach((path, item) -> {
            if (path.startsWith("/api/v1/demo-runs")) item.readOperations().forEach(operation -> {
                operation.setSecurity(properties.enabled()
                        ? java.util.List.of(new io.swagger.v3.oas.models.security.SecurityRequirement().addList("bearerAuth"))
                        : java.util.List.of());
            });
        });
    }
}
