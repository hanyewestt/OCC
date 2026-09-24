package com.csd.occ.ingestion.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

public record ArrivalFetch(OptionalInt httpStatus, Optional<String> payload, Optional<String> checksum,
                           int recordsReceived, Reason reason) {
    public ArrivalFetch {
        Objects.requireNonNull(httpStatus);
        Objects.requireNonNull(payload);
        Objects.requireNonNull(checksum);
        Objects.requireNonNull(reason);
        if (recordsReceived < 0 || (reason == Reason.NONE && (payload.isEmpty() || checksum.isEmpty()
                || httpStatus.isEmpty() || httpStatus.getAsInt() < 200 || httpStatus.getAsInt() >= 300))) {
            throw new IllegalArgumentException("Invalid fetch result");
        }
    }

    public boolean isSuccessful() { return reason == Reason.NONE; }

    public enum Reason { NONE, HTTP_ERROR, INVALID_RESPONSE, TRANSPORT_ERROR, INTERRUPTED, LEASE_EXPIRED }
}
