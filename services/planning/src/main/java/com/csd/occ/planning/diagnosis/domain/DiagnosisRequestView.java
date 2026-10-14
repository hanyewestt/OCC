package com.csd.occ.planning.diagnosis.domain;

import java.time.Instant;
import java.util.UUID;

public record DiagnosisRequestView(UUID requestId, UUID diagnosisId, DiagnosisRequestStatus status,
                                   String failureCode, Instant createdAt) { }
