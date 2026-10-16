package com.csd.occ.planning.infrastructure.config;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Optional HTTP API base for compatible Gemini endpoints, including local test doubles. */
@ConfigurationProperties("occ.planning.gemini")
public record GeminiEndpointProperties(String baseUrl) {
    public GeminiEndpointProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "";
        } else {
            URI uri;
            try {
                uri = URI.create(baseUrl);
            } catch (IllegalArgumentException invalidUri) {
                throw new IllegalArgumentException("base-url must be an absolute HTTP(S) URL", invalidUri);
            }
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException(
                        "base-url must be an absolute HTTP(S) URL without userinfo, query, or fragment");
            }
        }
    }

    public boolean isConfigured() {
        return !baseUrl.isBlank();
    }
}
