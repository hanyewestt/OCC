package com.csd.occ.ingestion.domain;

import java.time.Instant;
import java.util.List;

public interface RoutePositionRepository {
    List<RoutePositionRoute> findRoutesWithObservations(String serviceId, Instant from, Instant to);
}
