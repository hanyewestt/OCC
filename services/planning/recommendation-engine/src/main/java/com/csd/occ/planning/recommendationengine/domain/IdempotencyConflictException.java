package com.csd.occ.planning.recommendationengine.domain;

import java.util.UUID;

public final class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(UUID idempotencyKey) {
        super("Idempotency-Key " + idempotencyKey + " was already used with different request content.");
    }
}
