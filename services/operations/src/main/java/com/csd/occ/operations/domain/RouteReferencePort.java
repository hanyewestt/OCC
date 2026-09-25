package com.csd.occ.operations.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface RouteReferencePort {
    Optional<Route> route(String serviceId);

    record Route(String serviceId, List<Direction> directions) { }
    record Direction(String operator, int directionId, List<Stop> stops) { }
    record Stop(String stopCode, int stopSequence, BigDecimal latitude, BigDecimal longitude) { }
}
