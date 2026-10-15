package com.csd.occ.ingestion.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BusRouteStopRepository extends JpaRepository<BusRouteStop, BusRouteStopId> {
    long deleteByIdReferenceVersionId(UUID referenceVersionId);
    List<BusRouteStop> findByIdReferenceVersionIdAndIdServiceIdOrderByIdOperatorAscIdDirectionIdAscIdStopSequenceAsc(
            UUID referenceVersionId, String serviceId);
    List<BusRouteStop> findByIdReferenceVersionIdAndIdServiceIdAndIdOperatorOrderByIdDirectionIdAscIdStopSequenceAsc(
            UUID referenceVersionId, String serviceId, String operator);
    List<BusRouteStop> findByIdReferenceVersionIdAndIdServiceIdIn(UUID referenceVersionId, List<String> serviceIds);
}
