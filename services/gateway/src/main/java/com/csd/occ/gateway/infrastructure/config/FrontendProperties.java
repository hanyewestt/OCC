package com.csd.occ.gateway.infrastructure.config;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "occ.frontend")
public record FrontendProperties(URI projectUrl, String publishableKey) {
    public FrontendProperties {
        if (publishableKey != null && !publishableKey.isBlank()
                && !publishableKey.startsWith("sb_publishable_")) {
            throw new IllegalArgumentException("Frontend configuration accepts only a Supabase publishable key");
        }
    }
}
