package com.csd.occ.ingestion.infrastructure.config;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("occ.identity")
public record IdentityProviderProperties(boolean enabled, URI projectUrl, String secretKeyFile,
        Duration timeout, Duration directoryInterval) {
    public IdentityProviderProperties {
        if (timeout == null || timeout.compareTo(Duration.ofMillis(100)) < 0 || timeout.compareTo(Duration.ofMinutes(2)) > 0)
            throw new IllegalArgumentException("Identity timeout must be between 100ms and 2m");
        if (directoryInterval == null || directoryInterval.compareTo(Duration.ofSeconds(1)) < 0)
            throw new IllegalArgumentException("Identity directory interval must be at least 1s");
        if (enabled) {
            if (projectUrl == null || projectUrl.getHost() == null
                    || !("http".equals(projectUrl.getScheme()) || "https".equals(projectUrl.getScheme()))
                    || projectUrl.getUserInfo() != null || projectUrl.getQuery() != null || projectUrl.getFragment() != null
                    || (projectUrl.getPath() != null && !projectUrl.getPath().isEmpty() && !projectUrl.getPath().equals("/")))
                throw new IllegalArgumentException("Identity project URL must be an absolute HTTP(S) origin");
            if (secretKeyFile == null || secretKeyFile.isBlank())
                throw new IllegalArgumentException("Identity secret-key-file must be configured when enabled");
            try {
                Path path = Path.of(secretKeyFile);
                if (!Files.isRegularFile(path) || !Files.isReadable(path)) throw new IllegalArgumentException();
            } catch (RuntimeException invalidPath) {
                throw new IllegalArgumentException("Identity secret-key-file must be a readable mounted file");
            }
        }
    }

    @Override public String toString() {
        return "IdentityProviderProperties[enabled=" + enabled + ", secretKeyFile=REDACTED, timeout=" + timeout
                + ", directoryInterval=" + directoryInterval + "]";
    }
}
