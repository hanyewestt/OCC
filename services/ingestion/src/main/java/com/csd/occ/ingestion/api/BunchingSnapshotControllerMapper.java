package com.csd.occ.ingestion.api;
import com.csd.occ.ingestion.api.dto.*;
import com.csd.occ.ingestion.domain.BunchingFeedService;
final class BunchingSnapshotControllerMapper {
    private BunchingSnapshotControllerMapper() {}
    static BunchingSnapshotResponse response(BunchingFeedService.Snapshot value) {
        return new BunchingSnapshotResponse(value.sequence(), value.rawObservationId(), value.pollRunId(), value.sourceKey(), value.fetchedAt(), value.normalizationState(), value.arrivals().stream().map(a -> new BunchingArrivalResponse(a.observationId(), a.referenceVersionId(), a.stopCode(), a.serviceId(), a.directionId(), a.visitOrder(), a.estimatedArrivalAt(), a.originCode(), a.destinationCode(), a.latitude(), a.longitude())).toList());
    }
}
