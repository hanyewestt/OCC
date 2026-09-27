package com.csd.occ.diagnosis.domain;

import java.util.UUID;

public interface RecommendationSubmissionPort {
    DeliveryResult submit(UUID idempotencyKey, String payloadJson);

    record DeliveryResult(int httpStatus, String responseJson) {
    }
}
