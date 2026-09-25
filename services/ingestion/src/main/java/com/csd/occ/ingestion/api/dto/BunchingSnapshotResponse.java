package com.csd.occ.ingestion.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BunchingSnapshotResponse(long sequence, UUID rawObservationId, UUID pollRunId, String sourceKey,
                                       Instant fetchedAt, String normalizationState,
                                       List<BunchingArrivalResponse> arrivals) {
    public BunchingSnapshotResponse { arrivals = List.copyOf(arrivals); }
}
