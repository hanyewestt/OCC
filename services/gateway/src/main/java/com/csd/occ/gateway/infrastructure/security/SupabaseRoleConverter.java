package com.csd.occ.gateway.infrastructure.security;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class SupabaseRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {
    private static final Set<String> OCC_ROLES = Set.of("ADMIN", "CONTROLLER", "DRIVER", "UNASSIGNED");

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Object roleClaim = jwt.getClaims().get("occ_role");
        if (!(roleClaim instanceof String role)) return Set.of();
        String normalized = role.trim().toUpperCase(Locale.ROOT);
        if (!OCC_ROLES.contains(normalized)) return Set.of();
        return Set.of(new SimpleGrantedAuthority("ROLE_" + normalized));
    }
}
