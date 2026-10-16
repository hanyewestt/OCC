package com.csd.occ.ingestion.infrastructure.security;

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
public record SupabaseJwtProperties(@NotNull URI projectUrl, @NotNull URI jwksUrl, @NotBlank String audience,
        @NotNull Duration clockSkew, @NotEmpty List<@NotBlank String> allowedAlgorithms) {
    public SupabaseJwtProperties { allowedAlgorithms = List.copyOf(allowedAlgorithms); }
    public String issuer() {
        String value = projectUrl.toString();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value + "/auth/v1";
    }
}
