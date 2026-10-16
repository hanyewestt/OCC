package com.csd.occ.gateway.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "occ.gateway")
public record GatewayProperties(boolean authTestEnabled) { }
