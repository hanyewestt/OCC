package com.csd.occ.ingestion.domain;

import java.util.Set;

public class PageAccessPolicy {
    private static final Set<String> ROLES = Set.of("CONTROLLER", "CAPTAIN", "ADMIN", "UNASSIGNED");

    public String normalizedRole(Set<String> authorities) {
        return ROLES.stream().filter(role -> authorities.contains("ROLE_" + role))
                .findFirst().orElse("UNASSIGNED");
    }

    public boolean recognizes(String page) {
        return page.equals("admin") || page.equals("controller") || page.equals("captain");
    }

    public boolean permits(String page, String role) {
        return (page.equals("admin") && role.equals("ADMIN"))
                || (page.equals("controller") && role.equals("CONTROLLER"))
                || (page.equals("captain") && role.equals("CAPTAIN"));
    }
}
