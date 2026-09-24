package com.csd.occ.ingestion.infrastructure.persistence;

import com.csd.occ.ingestion.domain.BusReferencePort;
import com.csd.occ.ingestion.domain.ReferenceTopologyPort;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PostgresReferenceTopologyAdapter implements ReferenceTopologyPort {
    // Custom persistence is required because activation uses PostGIS geometry
    // construction and bulk inserts in one transaction.
    private static final String DATASET = "BUS_TOPOLOGY";
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public PostgresReferenceTopologyAdapter(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public Optional<ReferenceTopologyVersion> activeVersion() {
        List<ReferenceTopologyVersion> versions = jdbc.query("""
                select id, checksum from ingestion.reference_dataset_version
                where dataset = :dataset and status = 'ACTIVE'
                """, Map.of("dataset", DATASET), (row, index) -> new ReferenceTopologyVersion(
                row.getObject("id", UUID.class), row.getString("checksum")));
        return versions.stream().findFirst();
    }

    @Override
    @Transactional
    public ReferenceTopologyVersion replaceIfChanged(BusReferencePort.BusReferenceData data, String checksum) {
        Optional<ReferenceTopologyVersion> active = activeVersion();
        if (active.filter(version -> version.checksum().equals(checksum)).isPresent()) return active.orElseThrow();
        UUID id = UUID.randomUUID();
        OffsetDateTime now = Instant.now(clock).atOffset(ZoneOffset.UTC);
        jdbc.update("""
                insert into ingestion.reference_dataset_version (id, dataset, version_key, checksum, status, imported_at)
                values (:id, :dataset, :versionKey, :checksum, 'CANDIDATE', :importedAt)
                """, Map.of("id", id, "dataset", DATASET, "versionKey", checksum.substring(0, 16),
                "checksum", checksum, "importedAt", now));
        jdbc.batchUpdate("""
                insert into ingestion.bus_stop (reference_version_id, stop_code, road_name, description, location, imported_at)
                values (:referenceVersionId, :stopCode, :roadName, :description,
                        ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326), :importedAt)
                """, data.stops().stream().map(stop -> parameters(id, now, Map.of("stopCode", stop.stopCode(),
                "roadName", stop.roadName(), "description", stop.description(), "latitude", stop.latitude(),
                "longitude", stop.longitude()))).toArray(Map[]::new));
        jdbc.batchUpdate("""
                insert into ingestion.bus_service (reference_version_id, service_id, operator, category, imported_at)
                values (:referenceVersionId, :serviceId, :operator, :category, :importedAt)
                """, data.services().stream().map(service -> parameters(id, now,
                Map.of("serviceId", service.serviceId(), "operator", service.operator()), "category", service.category()))
                .toArray(Map[]::new));
        jdbc.batchUpdate("""
                insert into ingestion.bus_route_stop
                    (reference_version_id, service_id, operator, direction_id, stop_sequence, stop_code, distance_km, imported_at)
                values (:referenceVersionId, :serviceId, :operator, :directionId, :stopSequence, :stopCode, :distanceKm, :importedAt)
                """, data.routeStops().stream().map(route -> parameters(id, now, Map.of(
                "serviceId", route.serviceId(), "operator", route.operator(), "directionId", route.directionId(),
                "stopSequence", route.stopSequence(), "stopCode", route.stopCode()), "distanceKm", route.distanceKm()))
                .toArray(Map[]::new));
        active.ifPresent(version -> jdbc.update("""
                update ingestion.reference_dataset_version set status = 'SUPERSEDED'
                where id = :id
                """, Map.of("id", version.id())));
        jdbc.update("""
                update ingestion.reference_dataset_version
                set status = 'ACTIVE', activated_at = :activatedAt where id = :id
                """, Map.of("id", id, "activatedAt", now));
        return new ReferenceTopologyVersion(id, checksum);
    }

    @Override
    public List<String> stopsForServices(UUID referenceVersionId, List<String> serviceIds) {
        return jdbc.query("""
                select distinct stop_code from ingestion.bus_route_stop
                where reference_version_id = :referenceVersionId and service_id in (:serviceIds)
                order by stop_code
                """, Map.of("referenceVersionId", referenceVersionId, "serviceIds", serviceIds),
                (row, index) -> row.getString("stop_code"));
    }

    @Override
    public Page<BusRouteSummary> busRoutes(UUID referenceVersionId, Pageable pageable) {
        Map<String, Object> parameters = pageParameters(referenceVersionId, pageable);
        List<BusRouteSummary> routes = jdbc.query("""
                select service_id, operator, category from ingestion.bus_service
                where reference_version_id = :referenceVersionId
                order by service_id, operator
                limit :limit offset :offset
                """, parameters, (row, index) -> new BusRouteSummary(row.getString("service_id"),
                row.getString("operator"), row.getString("category")));
        long total = jdbc.queryForObject("""
                select count(*) from ingestion.bus_service where reference_version_id = :referenceVersionId
                """, Map.of("referenceVersionId", referenceVersionId), Long.class);
        return new PageImpl<>(routes, pageable, total);
    }

    @Override
    public Optional<BusRoute> busRoute(UUID referenceVersionId, String serviceId) {
        List<RouteStopRow> rows = jdbc.query("""
                select route.operator, service.category, route.direction_id, route.stop_sequence, route.distance_km,
                       stop.stop_code, stop.road_name, stop.description,
                       ST_Y(stop.location) as latitude, ST_X(stop.location) as longitude
                from ingestion.bus_route_stop route
                join ingestion.bus_service service on service.reference_version_id = route.reference_version_id
                    and service.service_id = route.service_id and service.operator = route.operator
                join ingestion.bus_stop stop on stop.reference_version_id = route.reference_version_id
                    and stop.stop_code = route.stop_code
                where route.reference_version_id = :referenceVersionId and route.service_id = :serviceId
                order by route.operator, route.direction_id, route.stop_sequence
                """, Map.of("referenceVersionId", referenceVersionId, "serviceId", serviceId), (row, index) ->
                new RouteStopRow(row.getString("operator"), row.getString("category"), row.getInt("direction_id"),
                        row.getInt("stop_sequence"), row.getBigDecimal("distance_km"), row.getString("stop_code"),
                        row.getString("road_name"), row.getString("description"), row.getBigDecimal("latitude"),
                        row.getBigDecimal("longitude")));
        if (rows.isEmpty()) return Optional.empty();

        Map<String, List<RouteStopRow>> byOperator = new LinkedHashMap<>();
        for (RouteStopRow row : rows) byOperator.computeIfAbsent(row.operator(), ignored -> new ArrayList<>()).add(row);
        List<BusRouteOperator> operators = byOperator.values().stream().map(this::operator).toList();
        return Optional.of(new BusRoute(serviceId, operators));
    }

    @Override
    public Page<BusStop> busStops(UUID referenceVersionId, Pageable pageable) {
        Map<String, Object> parameters = pageParameters(referenceVersionId, pageable);
        List<BusStop> stops = jdbc.query("""
                select stop_code, road_name, description, ST_Y(location) as latitude, ST_X(location) as longitude
                from ingestion.bus_stop where reference_version_id = :referenceVersionId
                order by stop_code limit :limit offset :offset
                """, parameters, (row, index) -> busStop(row.getString("stop_code"), row.getString("road_name"),
                row.getString("description"), row.getBigDecimal("latitude"), row.getBigDecimal("longitude")));
        long total = jdbc.queryForObject("""
                select count(*) from ingestion.bus_stop where reference_version_id = :referenceVersionId
                """, Map.of("referenceVersionId", referenceVersionId), Long.class);
        return new PageImpl<>(stops, pageable, total);
    }

    @Override
    public Optional<BusStop> busStop(UUID referenceVersionId, String stopCode) {
        List<BusStop> stops = jdbc.query("""
                select stop_code, road_name, description, ST_Y(location) as latitude, ST_X(location) as longitude
                from ingestion.bus_stop where reference_version_id = :referenceVersionId and stop_code = :stopCode
                """, Map.of("referenceVersionId", referenceVersionId, "stopCode", stopCode), (row, index) ->
                busStop(row.getString("stop_code"), row.getString("road_name"), row.getString("description"),
                        row.getBigDecimal("latitude"), row.getBigDecimal("longitude")));
        return stops.stream().findFirst();
    }

    private BusRouteOperator operator(List<RouteStopRow> rows) {
        RouteStopRow first = rows.getFirst();
        Map<Integer, List<BusRouteStop>> byDirection = new LinkedHashMap<>();
        for (RouteStopRow row : rows) {
            byDirection.computeIfAbsent(row.directionId(), ignored -> new ArrayList<>()).add(new BusRouteStop(
                    row.stopCode(), row.roadName(), row.description(), row.latitude(), row.longitude(),
                    row.stopSequence(), row.distanceKm()));
        }
        List<BusRouteDirection> directions = byDirection.entrySet().stream().map(entry ->
                new BusRouteDirection(entry.getKey(), List.copyOf(entry.getValue()))).toList();
        return new BusRouteOperator(first.operator(), first.category(), directions);
    }

    private Map<String, Object> pageParameters(UUID referenceVersionId, Pageable pageable) {
        return Map.of("referenceVersionId", referenceVersionId, "limit", pageable.getPageSize(),
                "offset", pageable.getOffset());
    }

    private BusStop busStop(String stopCode, String roadName, String description, BigDecimal latitude, BigDecimal longitude) {
        return new BusStop(stopCode, roadName, description, latitude, longitude);
    }

    private record RouteStopRow(String operator, String category, int directionId, int stopSequence,
                                BigDecimal distanceKm, String stopCode, String roadName, String description,
                                BigDecimal latitude, BigDecimal longitude) {
    }

    private Map<String, Object> parameters(UUID referenceVersionId, OffsetDateTime importedAt,
            Map<String, Object> values) {
        Map<String, Object> parameters = new HashMap<>(values);
        parameters.put("referenceVersionId", referenceVersionId);
        parameters.put("importedAt", importedAt);
        return parameters;
    }

    private Map<String, Object> parameters(UUID referenceVersionId, OffsetDateTime importedAt, Map<String, Object> values,
            String nullableName, Object nullableValue) {
        Map<String, Object> parameters = parameters(referenceVersionId, importedAt, values);
        parameters.put(nullableName, nullableValue);
        return parameters;
    }
}
