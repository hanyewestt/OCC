package com.csd.occ.operations.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class MapReferenceResponse {
    private MapReferenceResponse() { }
    public record ServicePage(List<Service> items, int page, int size, long totalItems, int totalPages) { }
    public record Service(String serviceId, String operator, String category) { }
    public record Route(String serviceId, List<Operator> operators) { }
    public record Operator(String operator, List<Direction> directions) { }
    public record Direction(int directionId, List<Stop> stops) { }
    public record Stop(String stopCode, String roadName, String description, BigDecimal latitude,
            BigDecimal longitude, int stopSequence) { }
    public record ArrivalPage(List<Arrival> items, int page, int size, long totalItems, int totalPages) { }
    // DataMall can omit direction, visit order and ETA; these remain nullable source evidence.
    public record Arrival(String stopCode, String serviceId, Integer directionId, Integer visitOrder,
            Instant estimatedArrivalAt, Instant fetchedAt, Long ageSeconds) { }
}
