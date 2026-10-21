package com.csd.occ.gateway.domain;

import java.util.Set;

public class PageAccessPolicy {
    private static final Set<String> ROLES = Set.of("CONTROLLER", "DRIVER", "ADMIN", "UNASSIGNED");

    public String normalizedRole(Set<String> authorities) {
        return ROLES.stream().filter(role -> authorities.contains("ROLE_" + role))
                .findFirst().orElse("UNASSIGNED");
    }

    public boolean recognizes(String page) {
        return page.equals("controller") || page.equals("captain");
    }

    public boolean permits(String page, String role) {
        return (page.equals("controller") && role.equals("CONTROLLER"))
                || (page.equals("captain") && role.equals("DRIVER"));
    }
}
