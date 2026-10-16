package com.csd.occ.gateway.infrastructure.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "occ.security.supabase")
public record SupabaseJwtProperties(
        @NotNull URI projectUrl,
        @NotNull URI jwksUrl,
        @NotBlank String audience,
        @NotNull Duration clockSkew,
        @NotEmpty List<@NotBlank String> allowedAlgorithms) {

    public SupabaseJwtProperties {
        allowedAlgorithms = List.copyOf(allowedAlgorithms);
    }

    public String issuer() {
        String project = projectUrl.toString();
        while (project.endsWith("/")) project = project.substring(0, project.length() - 1);
        return project + "/auth/v1";
    }
}
