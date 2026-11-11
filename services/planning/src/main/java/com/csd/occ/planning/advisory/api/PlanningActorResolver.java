package com.csd.occ.planning.advisory.api;

import com.csd.occ.planning.advisory.domain.PlanningActor;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

public final class PlanningActorResolver {
    private PlanningActorResolver() { }
    public static PlanningActor from(Jwt jwt, Authentication authentication) {
        var authorities = authentication.getAuthorities().stream().map(Object::toString).toList();
        PlanningActor.Role role = authorities.contains("ROLE_ADMIN") ? PlanningActor.Role.ADMIN
                : authorities.contains("ROLE_CONTROLLER") ? PlanningActor.Role.CONTROLLER
                : authorities.contains("ROLE_CAPTAIN") ? PlanningActor.Role.CAPTAIN : null;
        if (role == null) throw new com.csd.occ.planning.advisory.domain.AdvisoryForbiddenException();
        return new PlanningActor(jwt.getSubject(), role);
    }
}
