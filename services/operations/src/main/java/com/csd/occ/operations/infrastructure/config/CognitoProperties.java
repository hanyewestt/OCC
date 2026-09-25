package com.csd.occ.operations.infrastructure.config;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("occ.cognito")
public record CognitoProperties(URI issuerUri, String appClientId) {
    public CognitoProperties {
        if (issuerUri == null || !"https".equalsIgnoreCase(issuerUri.getScheme())) {
            throw new IllegalArgumentException("Cognito issuer URI must use HTTPS");
        }
        if (appClientId == null || appClientId.isBlank()) {
            throw new IllegalArgumentException("Cognito app client ID is required");
        }
    }
}
