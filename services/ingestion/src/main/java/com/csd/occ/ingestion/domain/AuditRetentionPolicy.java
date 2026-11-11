package com.csd.occ.ingestion.domain;

import java.time.Duration;

/** Provides the configured retention window for application audit records. */
public interface AuditRetentionPolicy {
    Duration retention();
}
