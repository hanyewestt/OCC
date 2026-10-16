package com.csd.occ.ingestion.infrastructure.security;

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
    static final String PLANNING_AUTHORITY = "PEER_PLANNING";
    private final PeerCapabilityProperties properties;

    PeerCapabilityAuthenticationFilter(PeerCapabilityProperties properties) { this.properties = properties; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = applicationPath(request);
        String authority = null;
        String expectedToken = null;
        if (HttpMethod.GET.matches(request.getMethod()) && (path.equals("/internal/v1/arrival-snapshots")
                || path.startsWith("/internal/v1/arrival-snapshots/")
                || path.matches("/internal/v1/demo-runs/[^/]+/arrival-snapshots(?:/.*)?")
                || path.equals("/internal/v1/traffic-incident-polls"))) {
            authority = DISRUPTION_AUTHORITY;
            expectedToken = properties.disruptionToIngestionToken();
        } else if (HttpMethod.GET.matches(request.getMethod()) && path.startsWith("/internal/v1/ingestion/demo-runs/")
                && path.endsWith("/planning-context")) {
            authority = PLANNING_AUTHORITY;
            expectedToken = properties.planningToIngestionToken();
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
