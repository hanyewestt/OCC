package com.csd.occ.ingestion.infrastructure.persistence;

import com.csd.occ.ingestion.domain.RoutePositionReading;
import com.csd.occ.ingestion.domain.RoutePositionRepository;
import com.csd.occ.ingestion.domain.RoutePositionRoute;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgisRoutePositionRepository implements RoutePositionRepository {
    private static final String ROUTE_POSITION_QUERY = """
            WITH active_topology AS (
                SELECT id FROM ingestion.reference_dataset_version
                WHERE dataset = 'BUS_TOPOLOGY' AND status = 'ACTIVE'
            ), route_lines AS (
                SELECT route.reference_version_id, route.service_id, route.operator, route.direction_id,
                       ST_MakeLine(ST_Transform(stop.location, 3414) ORDER BY route.stop_sequence) AS route_line
                FROM ingestion.bus_route_stop route
                JOIN active_topology active ON active.id = route.reference_version_id
                JOIN ingestion.bus_stop stop
                  ON stop.reference_version_id = route.reference_version_id AND stop.stop_code = route.stop_code
                WHERE route.service_id = ?
                GROUP BY route.reference_version_id, route.service_id, route.operator, route.direction_id
                HAVING count(*) >= 2
            )
            SELECT route.operator, route.direction_id,
                   ST_Length(route.route_line) AS route_length_m,
                   CASE WHEN observation.id IS NULL THEN NULL ELSE
                       ST_LineLocatePoint(route.route_line,
                           ST_Transform(ST_SetSRID(ST_MakePoint(observation.longitude::double precision,
                               observation.latitude::double precision), 4326), 3414))
                       * ST_Length(route.route_line)
                   END AS distance_along_route_m,
                   observation.fetched_at
            FROM route_lines route
            LEFT JOIN ingestion.bus_arrival_observation observation
              ON observation.service_id = route.service_id
             AND observation.classification = 'OBSERVATION_ONLY'
             AND observation.latitude BETWEEN -90 AND 90
             AND observation.longitude BETWEEN -180 AND 180
             AND NOT (observation.latitude = 0 AND observation.longitude = 0)
             AND observation.fetched_at BETWEEN ? AND ?
             AND EXISTS (
                 SELECT 1 FROM ingestion.bus_route_stop match
                 WHERE match.reference_version_id = route.reference_version_id
                   AND match.service_id = route.service_id
                   AND match.operator = route.operator
                   AND match.direction_id = route.direction_id
                   AND match.stop_code = observation.stop_code
             )
             AND (
                 observation.direction_id = route.direction_id
                 OR (observation.direction_id IS NULL AND 1 = (
                     SELECT count(DISTINCT match.direction_id)
                     FROM ingestion.bus_route_stop match
                     WHERE match.reference_version_id = route.reference_version_id
                       AND match.service_id = route.service_id
                       AND match.operator = route.operator
                       AND match.stop_code = observation.stop_code
                 ))
             )
            ORDER BY route.operator, route.direction_id, observation.fetched_at
            """;

    private final JdbcTemplate jdbc;

    public PostgisRoutePositionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<RoutePositionRoute> findRoutesWithObservations(String serviceId, Instant from, Instant to) {
        List<RoutePositionRow> rows = jdbc.query(ROUTE_POSITION_QUERY, statement -> {
            statement.setString(1, serviceId);
            statement.setTimestamp(2, Timestamp.from(from));
            statement.setTimestamp(3, Timestamp.from(to));
        }, (result, rowNumber) -> new RoutePositionRow(result.getString("operator"),
                result.getInt("direction_id"), result.getDouble("route_length_m"),
                result.getObject("distance_along_route_m") == null ? null : result.getDouble("distance_along_route_m"),
                result.getTimestamp("fetched_at") == null ? null : result.getTimestamp("fetched_at").toInstant()));

        Map<RouteKey, RouteBuilder> routes = new LinkedHashMap<>();
        for (RoutePositionRow row : rows) {
            RouteKey key = new RouteKey(row.operator(), row.directionId());
            RouteBuilder route = routes.computeIfAbsent(key, ignored -> new RouteBuilder(row.routeLengthMeters()));
            if (row.distanceAlongRouteMeters() != null && row.fetchedAt() != null)
                route.readings().add(new RoutePositionReading(row.distanceAlongRouteMeters(), row.fetchedAt()));
        }
        return routes.entrySet().stream().map(entry -> new RoutePositionRoute(entry.getKey().operator(),
                entry.getKey().directionId(), entry.getValue().routeLengthMeters(),
                List.copyOf(entry.getValue().readings()))).toList();
    }

    private record RouteKey(String operator, int directionId) {
    }

    private record RoutePositionRow(String operator, int directionId, double routeLengthMeters,
                                    Double distanceAlongRouteMeters, Instant fetchedAt) {
    }

    private static final class RouteBuilder {
        private final double routeLengthMeters;
        private final List<RoutePositionReading> readings = new ArrayList<>();

        private RouteBuilder(double routeLengthMeters) {
            this.routeLengthMeters = routeLengthMeters;
        }

        private double routeLengthMeters() { return routeLengthMeters; }
        private List<RoutePositionReading> readings() { return readings; }
    }
}
