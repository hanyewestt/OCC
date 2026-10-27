package com.csd.occ.ingestion.infrastructure.config;

import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("occ.ingestion.demo-captain")
public record DemoCaptainProperties(String userId) {
    public DemoCaptainProperties {
        userId = userId == null ? "" : userId.trim();
        if (!userId.isEmpty()) UUID.fromString(userId);
    }

    public Optional<UUID> subject() {
        return userId.isEmpty() ? Optional.empty() : Optional.of(UUID.fromString(userId));
    }
}
