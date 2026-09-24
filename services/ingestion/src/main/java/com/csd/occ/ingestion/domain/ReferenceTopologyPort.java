package com.csd.occ.ingestion.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ReferenceTopologyPort {
    Optional<ReferenceTopologyVersion> activeVersion();
    ReferenceTopologyVersion replaceIfChanged(BusReferencePort.BusReferenceData data, String checksum);
    List<String> stopsForServices(UUID referenceVersionId, List<String> serviceIds);
    Page<BusRouteSummary> busRoutes(UUID referenceVersionId, Pageable pageable);
    Optional<BusRoute> busRoute(UUID referenceVersionId, String serviceId);
    Page<BusStop> busStops(UUID referenceVersionId, Pageable pageable);
    Optional<BusStop> busStop(UUID referenceVersionId, String stopCode);

    record ReferenceTopologyVersion(UUID id, String checksum) {
    }

    record BusRouteSummary(String serviceId, String operator, String category) {
    }

    record BusRoute(String serviceId, List<BusRouteOperator> operators) {
    }

    record BusRouteOperator(String operator, String category, List<BusRouteDirection> directions) {
    }

    record BusRouteDirection(int directionId, List<BusRouteStop> stops) {
    }

    record BusRouteStop(String stopCode, String roadName, String description, BigDecimal latitude,
                        BigDecimal longitude, int stopSequence, BigDecimal distanceKm) {
    }

    record BusStop(String stopCode, String roadName, String description, BigDecimal latitude, BigDecimal longitude) {
    }
}
