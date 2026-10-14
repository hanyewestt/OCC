package com.csd.occ.planning.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

final class PeerCapabilityAuthenticationFilter extends OncePerRequestFilter {
    static final String HEADER_NAME = "X-OCC-Peer-Token";
    static final String DISRUPTION_AUTHORITY = "PEER_DISRUPTION";
    static final String INGESTION_AUTHORITY = "PEER_INGESTION";
    private static final String REQUEST_PATH = "/internal/v1/planning/requests";

    private final PeerCapabilityProperties properties;

    PeerCapabilityAuthenticationFilter(PeerCapabilityProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = applicationPath(request);
        String authority = null;
        String expectedToken = null;
        if (HttpMethod.POST.matches(request.getMethod()) && REQUEST_PATH.equals(path)) {
            authority = DISRUPTION_AUTHORITY;
            expectedToken = properties.disruptionToPlanningToken();
        } else if (HttpMethod.GET.matches(request.getMethod())
                && path.matches("/internal/v1/planning/recommendations/by-disruption-event/[^/]+")) {
            authority = INGESTION_AUTHORITY;
            expectedToken = properties.ingestionToPlanningToken();
        }
        if (authority != null && matchesConfiguredToken(expectedToken, request.getHeader(HEADER_NAME))) {
            var authentication = UsernamePasswordAuthenticationToken.authenticated(
                    authority.equals(DISRUPTION_AUTHORITY) ? "disruption-service" : "ingestion-service", "N/A",
                    List.of(new SimpleGrantedAuthority(authority)));
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        chain.doFilter(request, response);
    }

    private static String applicationPath(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        return requestUri.startsWith(contextPath) ? requestUri.substring(contextPath.length()) : requestUri;
    }

    private boolean matchesConfiguredToken(String expected, String supplied) {
        if (supplied == null || supplied.isBlank()) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }
}
