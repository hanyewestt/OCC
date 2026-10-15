package com.csd.occ.disruption.infrastructure.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties({SupabaseJwtProperties.class, PeerCapabilityProperties.class})
public class DisruptionSecurityConfig {
    @Bean
    SecurityFilterChain disruptionSecurityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder,
            SupabaseRoleConverter roleConverter, PeerCapabilityProperties peerProperties) throws Exception {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roleConverter);
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/prometheus").hasRole("ADMIN")
                        .requestMatchers("/actuator/**").denyAll()
                        .requestMatchers(HttpMethod.GET, "/internal/v1/disruption/retention-protection",
                                "/internal/v1/disruption/demo-runs/**", "/internal/v1/disruption/live-bunching-alerts")
                        .hasAuthority(PeerCapabilityAuthenticationFilter.INGESTION_AUTHORITY)
                        .requestMatchers(HttpMethod.PUT, "/internal/v1/disruption/consumer-selection")
                        .hasAuthority(PeerCapabilityAuthenticationFilter.INGESTION_AUTHORITY)
                        .requestMatchers(HttpMethod.GET, "/internal/v1/disruption/events/**")
                        .hasAuthority(PeerCapabilityAuthenticationFilter.PLANNING_AUTHORITY)
                        .requestMatchers("/internal/v1/**").denyAll()
                        .requestMatchers("/api/v1/**").hasAnyRole("ADMIN", "CONTROLLER")
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                        .hasAnyRole("ADMIN", "CONTROLLER")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(server -> server.jwt(jwt -> jwt.decoder(jwtDecoder)
                        .jwtAuthenticationConverter(converter)))
                .addFilterBefore(new PeerCapabilityAuthenticationFilter(peerProperties), AuthorizationFilter.class)
                .build();
    }

    @Bean
    JwtDecoder disruptionJwtDecoder(SupabaseJwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwksUrl().toString())
                .jwsAlgorithms(algorithms -> {
                    algorithms.clear();
                    properties.allowedAlgorithms().stream().map(SignatureAlgorithm::valueOf).forEach(algorithms::add);
                }).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtIssuerValidator(properties.issuer()),
                new JwtTimestampValidator(properties.clockSkew()),
                (OAuth2TokenValidator<Jwt>) jwt -> jwt.getAudience().contains(properties.audience())
                        ? OAuth2TokenValidatorResult.success() : invalidToken("Token audience is not accepted"),
                (OAuth2TokenValidator<Jwt>) jwt -> jwt.getSubject() != null && !jwt.getSubject().isBlank()
                        ? OAuth2TokenValidatorResult.success() : invalidToken("Token subject is required")));
        return decoder;
    }

    private static OAuth2TokenValidatorResult invalidToken(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", description, null));
    }
}
