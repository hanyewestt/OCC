package com.csd.occ.ingestion.api;
import com.csd.occ.ingestion.api.dto.*;
import com.csd.occ.ingestion.domain.BunchingFeedService;
final class BunchingSnapshotControllerMapper {
    private BunchingSnapshotControllerMapper() {}
    static BunchingSnapshotResponse response(BunchingFeedService.Snapshot value) {
        return new BunchingSnapshotResponse(value.sequence(), value.rawObservationId(), value.pollRunId(),
                value.sourcePollCycleId(), value.sourceKey(), value.fetchedAt(), value.normalizationState(),
                value.arrivals().stream().map(arrival -> new BunchingArrivalResponse(arrival.observationId(),
                        arrival.referenceVersionId(), arrival.stopCode(), arrival.serviceId(), arrival.operator(),
                        arrival.directionId(), arrival.visitOrder(), arrival.visitNumber(),
                        arrival.estimatedArrivalAt(), arrival.originCode(), arrival.destinationCode(),
                        arrival.latitude(), arrival.longitude(), arrival.routeStopSequence())).toList());
    }
}
