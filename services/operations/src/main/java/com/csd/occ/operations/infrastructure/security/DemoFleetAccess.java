package com.csd.occ.operations.infrastructure.security;

import com.csd.occ.operations.infrastructure.config.CognitoProperties;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component("demoFleetAccess")
public class DemoFleetAccess {
    private final CognitoProperties properties;
    public DemoFleetAccess(CognitoProperties properties) { this.properties = properties; }
    public boolean allowed(Authentication authentication) {
        return !properties.enabled() || authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream().anyMatch(authority ->
                    authority.getAuthority().equals("ROLE_ADMIN") || authority.getAuthority().equals("ROLE_CONTROLLER"));
    }
}
