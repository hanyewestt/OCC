package com.csd.occ.operations.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class CognitoAccessTokenValidatorTest {
    private final CognitoAccessTokenValidator validator = new CognitoAccessTokenValidator("operations-client");

    @Test
    void acceptsOnlyAccessTokensForTheConfiguredClient() {
        assertThat(validator.validate(token("access", "operations-client")).hasErrors()).isFalse();
        assertThat(validator.validate(token("id", "operations-client")).hasErrors()).isTrue();
        assertThat(validator.validate(token("access", "another-client")).hasErrors()).isTrue();
    }

    private Jwt token(String use, String clientId) {
        return Jwt.withTokenValue("synthetic")
                .header("alg", "none")
                .claim("token_use", use)
                .claim("client_id", clientId)
                .build();
    }
}
