package com.csd.occ.ingestion.infrastructure.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "occ.security.peer-clients")
public record PeerClientProperties(@NotBlank String disruptionToken, @NotBlank String planningToken) { }
