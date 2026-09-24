package com.csd.occ.ingestion.domain;

import java.math.BigDecimal;
import java.util.List;

public interface BusReferencePort {
    BusReferenceData fetch();

    record BusReferenceData(List<BusStopRecord> stops, List<BusServiceRecord> services,
            List<BusRouteStopRecord> routeStops) {
    }

    record BusStopRecord(String stopCode, String roadName, String description,
            BigDecimal latitude, BigDecimal longitude) {
    }

    record BusServiceRecord(String serviceId, String operator, String category) {
    }

    record BusRouteStopRecord(String serviceId, String operator, int directionId,
            int stopSequence, String stopCode, BigDecimal distanceKm) {
    }
}
