package com.csd.occ.disruption.infrastructure.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.stereotype.Component;

@Component
public class AuditAuthenticationEntryPoint implements AuthenticationEntryPoint {
    private static final Logger log = LoggerFactory.getLogger(AuditAuthenticationEntryPoint.class);
    private final AuthorizationFailureAuditCapture audit;
    private final BearerTokenAuthenticationEntryPoint delegate = new BearerTokenAuthenticationEntryPoint();

    public AuditAuthenticationEntryPoint(AuthorizationFailureAuditCapture audit) { this.audit = audit; }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException, ServletException {
        try { audit.record(request, "AUTHENTICATION_REQUIRED", SecurityContextHolder.getContext().getAuthentication()); }
        catch (RuntimeException failure) { log.warn("Could not record a bounded authorization failure event"); }
        delegate.commence(request, response, exception);
    }
}
