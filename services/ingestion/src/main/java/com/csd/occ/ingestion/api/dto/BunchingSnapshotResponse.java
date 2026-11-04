package com.csd.occ.ingestion.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BunchingSnapshotResponse(long sequence, UUID rawObservationId, UUID pollRunId,
                                       @Schema(nullable = true,
                                               description = "Coordinated provider poll cycle used for conservative diagnosis correlation; null when unavailable")
                                       UUID sourcePollCycleId,
                                       String sourceKey, Instant fetchedAt, String normalizationState,
                                       List<BunchingArrivalResponse> arrivals) {
    public BunchingSnapshotResponse { arrivals = List.copyOf(arrivals); }
}
