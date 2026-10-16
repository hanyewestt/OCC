package com.csd.occ.planning.diagnosis.domain;

public interface EventSubmissionFingerprintPort {
    String fingerprint(ProducerEventSubmission submission);
}
