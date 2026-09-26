package com.csd.occ.operations.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface MapReferencePort {
    ServicePage services(int page, int size);
    Route route(String serviceId);
    ArrivalPage arrivals(String serviceId, String stopCode, int page, int size);
    record ServicePage(List<Service> items, int page, int size, long totalItems, int totalPages) { }
    record Service(String serviceId, String operator, String category) { }
    record Route(String serviceId, List<Operator> operators) { }
    record Operator(String operator, List<Direction> directions) { }
    record Direction(int directionId, List<Stop> stops) { }
    record Stop(String stopCode, String roadName, String description, BigDecimal latitude,
            BigDecimal longitude, int stopSequence) { }
    record ArrivalPage(List<Arrival> items, int page, int size, long totalItems, int totalPages) { }
    // DataMall can omit direction, visit order and ETA; these remain nullable source evidence.
    record Arrival(String stopCode, String serviceId, Integer directionId, Integer visitOrder,
            Instant estimatedArrivalAt, Instant fetchedAt, Long ageSeconds) { }
}
