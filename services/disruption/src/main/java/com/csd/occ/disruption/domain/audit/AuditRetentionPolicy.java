package com.csd.occ.disruption.domain.audit;

import java.time.Duration;

public interface AuditRetentionPolicy {
    Duration retention();
}
