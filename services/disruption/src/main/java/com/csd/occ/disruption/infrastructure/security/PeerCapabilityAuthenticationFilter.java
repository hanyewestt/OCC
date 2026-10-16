package com.csd.occ.disruption.infrastructure.security;

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
    static final String INGESTION_AUTHORITY = "PEER_INGESTION";
    static final String PLANNING_AUTHORITY = "PEER_PLANNING";
    private final PeerCapabilityProperties properties;

    PeerCapabilityAuthenticationFilter(PeerCapabilityProperties properties) { this.properties = properties; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = applicationPath(request);
        String authority = null;
        String expectedToken = null;
        if (HttpMethod.GET.matches(request.getMethod()) && path.equals("/internal/v1/disruption/retention-protection")
                || HttpMethod.PUT.matches(request.getMethod()) && path.equals("/internal/v1/disruption/consumer-selection")
                || HttpMethod.GET.matches(request.getMethod()) && (path.startsWith("/internal/v1/disruption/demo-runs/")
                        || path.equals("/internal/v1/disruption/live-bunching-alerts"))) {
            authority = INGESTION_AUTHORITY;
            expectedToken = properties.ingestionToDisruptionToken();
        } else if (HttpMethod.GET.matches(request.getMethod()) && path.startsWith("/internal/v1/disruption/events/")) {
            authority = PLANNING_AUTHORITY;
            expectedToken = properties.planningToDisruptionToken();
        }
        if (authority != null && matches(expectedToken, request.getHeader(HEADER_NAME))) {
            var authentication = UsernamePasswordAuthenticationToken.authenticated("occ-peer", "N/A",
                    List.of(new SimpleGrantedAuthority(authority)));
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        chain.doFilter(request, response);
    }

    private static boolean matches(String expected, String supplied) {
        return supplied != null && !supplied.isBlank() && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }

    private static String applicationPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return uri.startsWith(context) ? uri.substring(context.length()) : uri;
    }
}
