package com.csd.occ.planning.infrastructure.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "occ.security.peer")
public record PeerCapabilityProperties(@NotBlank String disruptionToPlanningToken,
        @NotBlank String ingestionToPlanningToken) {}
