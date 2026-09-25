package com.csd.occ.operations.infrastructure.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

public class CognitoAccessTokenValidator implements OAuth2TokenValidator<Jwt> {
    private final String appClientId;

    public CognitoAccessTokenValidator(String appClientId) { this.appClientId = appClientId; }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        if (!"access".equals(token.getClaimAsString("token_use"))
                || !appClientId.equals(token.getClaimAsString("client_id"))) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                    "Expected a Cognito access token for the configured app client", null));
        }
        return OAuth2TokenValidatorResult.success();
    }
}
