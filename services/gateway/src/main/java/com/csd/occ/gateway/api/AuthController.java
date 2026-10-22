package com.csd.occ.gateway.api;

import com.csd.occ.gateway.api.dto.SessionResponse;
import com.csd.occ.gateway.domain.PageAccessPolicy;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.stream.Collectors;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@SecurityRequirement(name = "bearerAuth")
public class AuthController {
    private final PageAccessPolicy access = new PageAccessPolicy();

    @GetMapping(value = "/api/v1/auth/session", produces = "application/json")
    @Operation(operationId = "readSession", summary = "Read the verified subject and OCC role")
    @ApiResponse(responseCode = "200", description = "Verified session; unknown roles normalize to UNASSIGNED")
    @ApiResponse(responseCode = "401", description = "Missing, invalid or expired bearer token", content = @Content)
    public ResponseEntity<SessionResponse> session(Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(identity(authentication));
    }

    @GetMapping(value = "/api/v1/auth/access/{page}", produces = "application/json")
    @Operation(operationId = "verifyPageAccess", summary = "Verify exclusive controller or captain access",
            description = "controller requires CONTROLLER; captain requires CAPTAIN. ADMIN has no override.")
    @ApiResponse(responseCode = "200", description = "Role permits this page")
    @ApiResponse(responseCode = "401", description = "Missing, invalid or expired bearer token", content = @Content)
    @ApiResponse(responseCode = "403", description = "Role does not permit this page",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Unknown page",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<SessionResponse> pageAccess(@PathVariable String page, Authentication authentication) {
        SessionResponse identity = identity(authentication);
        if (!access.recognizes(page)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown page");
        if (!access.permits(page, identity.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This role cannot access this page");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(identity);
    }

    private SessionResponse identity(Authentication authentication) {
        var authorities = authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority()).collect(Collectors.toSet());
        return new SessionResponse(authentication.getName(), access.normalizedRole(authorities));
    }
}
