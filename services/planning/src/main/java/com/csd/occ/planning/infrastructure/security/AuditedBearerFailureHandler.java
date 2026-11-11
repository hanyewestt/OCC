package com.csd.occ.planning.infrastructure.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

@Component
public class AuditedBearerFailureHandler implements AuthenticationEntryPoint, AccessDeniedHandler {
    private final ApplicationAuthorizationFailureCapture capture;
    private final BearerTokenAuthenticationEntryPoint authenticationEntryPoint = new BearerTokenAuthenticationEntryPoint();
    private final BearerTokenAccessDeniedHandler accessDeniedHandler = new BearerTokenAccessDeniedHandler();

    public AuditedBearerFailureHandler(ApplicationAuthorizationFailureCapture capture) {
        this.capture = capture;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException, ServletException {
        captureSafely(request, 401, null);
        authenticationEntryPoint.commence(request, response, exception);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            org.springframework.security.access.AccessDeniedException exception) throws IOException, ServletException {
        captureSafely(request, 403, org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication());
        accessDeniedHandler.handle(request, response, exception);
    }

    private void captureSafely(HttpServletRequest request, int status, Authentication authentication) {
        try {
            capture.record(request, status, authentication);
        } catch (RuntimeException ignored) {
            // A failure to record an audit event must not change the established authentication response.
        }
    }
}
