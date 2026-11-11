package com.csd.occ.planning.domain.audit;

import java.time.Duration;

public interface AuditRetentionPolicy {
    Duration retention();
}
