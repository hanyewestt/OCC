package com.csd.occ.ingestion.infrastructure.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

@Component
public class AuditAccessDeniedHandler implements AccessDeniedHandler {
    private final AuthorizationFailureAuditCapture capture;
    private final BearerTokenAccessDeniedHandler delegate = new BearerTokenAccessDeniedHandler();

    public AuditAccessDeniedHandler(AuthorizationFailureAuditCapture capture) {
        this.capture = capture;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            org.springframework.security.access.AccessDeniedException exception) throws IOException, ServletException {
        try {
            capture.record(request, true, SecurityContextHolder.getContext().getAuthentication());
        } catch (RuntimeException ignored) {
            // Auditing must never alter the authorization denial response.
        }
        delegate.handle(request, response, exception);
    }
}
