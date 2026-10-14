package com.csd.occ.planning.diagnosis.api.dto;

import com.csd.occ.planning.diagnosis.domain.DiagnosisRequestStatus;
import java.time.Instant;
import java.util.UUID;

public record DiagnosisRequestResponse(UUID requestId, UUID diagnosisId, DiagnosisRequestStatus status, String failureCode,
                                      Instant createdAt) { }
