package com.csd.occ.gateway.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class SupabaseRoleConverterTest {
    private final SupabaseRoleConverter converter = new SupabaseRoleConverter();

    @Test
    void mapsTheCustomOccRoleClaimToOneRoleAuthority() {
        Jwt jwt = Jwt.withTokenValue("synthetic")
                .header("alg", "ES256")
                .claim("occ_role", "CONTROLLER")
                .build();

        assertThat(converter.convert(jwt)).containsExactly(new SimpleGrantedAuthority("ROLE_CONTROLLER"));
    }

    @Test
    void ignoresUnknownAndNonStringRoleClaims() {
        Jwt unknown = Jwt.withTokenValue("synthetic").header("alg", "ES256").claim("occ_role", "OWNER").build();
        Jwt malformed = Jwt.withTokenValue("synthetic").header("alg", "ES256").claim("occ_role", Set.of("ADMIN")).build();

        assertThat(converter.convert(unknown)).isEmpty();
        assertThat(converter.convert(malformed)).isEmpty();
    }
}
