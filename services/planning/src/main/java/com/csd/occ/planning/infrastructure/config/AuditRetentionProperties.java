package com.csd.occ.planning.infrastructure.config;

import com.csd.occ.planning.domain.audit.AuditRetentionPolicy;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("occ.audit")
public record AuditRetentionProperties(Duration retention) implements AuditRetentionPolicy {
    public AuditRetentionProperties {
        if (retention == null || retention.isZero() || retention.isNegative())
            throw new IllegalArgumentException("Audit retention must be a positive duration");
    }
}
