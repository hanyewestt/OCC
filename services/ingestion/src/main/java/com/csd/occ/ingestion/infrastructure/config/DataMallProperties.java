package com.csd.occ.ingestion.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("occ.datamall")
public record DataMallProperties(String baseUrl, String accountKey) {}
