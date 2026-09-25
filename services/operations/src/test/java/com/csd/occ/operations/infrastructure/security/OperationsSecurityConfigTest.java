package com.csd.occ.operations.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class OperationsSecurityConfigTest {
    @Test
    void onlyKnownCognitoGroupsBecomeApplicationRoles() {
        Jwt jwt = Jwt.withTokenValue("synthetic")
                .header("alg", "none")
                .subject("controller-1")
                .claim("cognito:groups", List.of("CONTROLLER", "UNTRUSTED"))
                .build();

        var authentication = new OperationsSecurityConfig().cognitoGroupsConverter().convert(jwt);

        assertThat(authentication.getName()).isEqualTo("controller-1");
        assertThat(authentication.getAuthorities()).extracting("authority")
                .containsExactly("ROLE_CONTROLLER");
    }
}
