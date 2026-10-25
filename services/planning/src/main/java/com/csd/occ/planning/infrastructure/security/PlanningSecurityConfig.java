package com.csd.occ.planning.infrastructure.security;

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
public class PlanningSecurityConfig {
    @Bean
    SecurityFilterChain planningSecurityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder,
            SupabaseRoleConverter roleConverter, PeerCapabilityProperties peerProperties) throws Exception {
        JwtAuthenticationConverter authenticationConverter = new JwtAuthenticationConverter();
        authenticationConverter.setJwtGrantedAuthoritiesConverter(roleConverter);

        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/prometheus").hasRole("ADMIN")
                        .requestMatchers("/actuator/**").denyAll()
                        .requestMatchers(HttpMethod.POST, "/internal/v1/planning/requests")
                        .hasAuthority(PeerCapabilityAuthenticationFilter.DISRUPTION_AUTHORITY)
                        .requestMatchers(HttpMethod.GET,
                                "/internal/v1/planning/recommendations/by-disruption-event/*")
                        .hasAuthority(PeerCapabilityAuthenticationFilter.INGESTION_AUTHORITY)
                        .requestMatchers("/internal/v1/**").denyAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/planning/bus-instructions")
                        .hasAnyRole("ADMIN", "CONTROLLER", "CAPTAIN")
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/planning/bus-instructions/*/acknowledgement")
                        .hasAnyRole("ADMIN", "CAPTAIN")
                        .requestMatchers("/api/v1/**").hasAnyRole("ADMIN", "CONTROLLER")
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                        .hasAnyRole("ADMIN", "CONTROLLER")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(authenticationConverter)))
                .addFilterBefore(new PeerCapabilityAuthenticationFilter(peerProperties), AuthorizationFilter.class)
                .build();
    }

    @Bean
    JwtDecoder planningJwtDecoder(SupabaseJwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwksUrl().toString())
                .jwsAlgorithms(algorithms -> {
                    algorithms.clear();
                    properties.allowedAlgorithms().stream()
                            .map(SignatureAlgorithm::valueOf)
                            .forEach(algorithms::add);
                })
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtIssuerValidator(properties.issuer()),
                new JwtTimestampValidator(properties.clockSkew()),
                audienceValidator(properties.audience()),
                subjectValidator()));
        return decoder;
    }

    private static OAuth2TokenValidator<Jwt> audienceValidator(String audience) {
        return jwt -> jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : invalidToken("Token audience is not accepted");
    }

    private static OAuth2TokenValidator<Jwt> subjectValidator() {
        return jwt -> jwt.getSubject() != null && !jwt.getSubject().isBlank()
                ? OAuth2TokenValidatorResult.success()
                : invalidToken("Token subject is required");
    }

    private static OAuth2TokenValidatorResult invalidToken(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", description, null));
    }
}
