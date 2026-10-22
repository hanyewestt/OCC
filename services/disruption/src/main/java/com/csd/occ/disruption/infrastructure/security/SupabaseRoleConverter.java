package com.csd.occ.disruption.infrastructure.security;

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
    private static final Set<String> ROLES = Set.of("ADMIN", "CONTROLLER", "CAPTAIN", "UNASSIGNED");
    @Override public Collection<GrantedAuthority> convert(Jwt jwt) {
        Object claim = jwt.getClaims().get("occ_role");
        if (!(claim instanceof String role)) return Set.of();
        String normalized = role.trim().toUpperCase(Locale.ROOT);
        return ROLES.contains(normalized) ? Set.of(new SimpleGrantedAuthority("ROLE_" + normalized)) : Set.of();
    }
}
