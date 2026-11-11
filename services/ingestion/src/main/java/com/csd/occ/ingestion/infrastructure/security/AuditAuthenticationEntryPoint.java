package com.csd.occ.ingestion.infrastructure.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

@Component
public class AuditAuthenticationEntryPoint implements AuthenticationEntryPoint {
    private final AuthorizationFailureAuditCapture capture;
    private final BearerTokenAuthenticationEntryPoint delegate = new BearerTokenAuthenticationEntryPoint();

    public AuditAuthenticationEntryPoint(AuthorizationFailureAuditCapture capture) {
        this.capture = capture;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            org.springframework.security.core.AuthenticationException exception) throws IOException, ServletException {
        try {
            capture.record(request, false, SecurityContextHolder.getContext().getAuthentication());
        } catch (RuntimeException ignored) {
            // Auditing must never alter the authentication denial response.
        }
        delegate.commence(request, response, exception);
    }
}
