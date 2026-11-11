package com.csd.occ.ingestion.domain;

import java.time.Instant;

public interface DirectoryAuditCapturePort {
    void failure(Instant startedAt, String safeReason);
}
