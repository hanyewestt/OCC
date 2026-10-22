package com.csd.occ.disruption.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class SupabaseRoleConverterTest {
    private final SupabaseRoleConverter converter = new SupabaseRoleConverter();

    @Test
    void acceptsCaptainAndRejectsRetiredDriverClaims() {
        Jwt captain = Jwt.withTokenValue("synthetic").header("alg", "ES256")
                .claim("occ_role", "CAPTAIN").build();
        Jwt retired = Jwt.withTokenValue("synthetic").header("alg", "ES256")
                .claim("occ_role", "DRIVER").build();

        assertThat(converter.convert(captain)).containsExactly(new SimpleGrantedAuthority("ROLE_CAPTAIN"));
        assertThat(converter.convert(retired)).isEmpty();
    }
}
