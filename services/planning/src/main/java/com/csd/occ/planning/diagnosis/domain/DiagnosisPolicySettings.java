package com.csd.occ.planning.diagnosis.domain;

import java.time.Duration;

public record DiagnosisPolicySettings(Duration evidenceLookback, double evidenceRadiusKm,
                                      double confidenceThreshold, int maxAttempts,
                                      Duration retryInitialDelay, Duration retryMaxDelay) { }
