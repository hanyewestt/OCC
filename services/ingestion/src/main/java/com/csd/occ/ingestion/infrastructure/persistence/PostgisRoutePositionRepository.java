package com.csd.occ.ingestion.infrastructure.persistence;

import com.csd.occ.ingestion.domain.RoutePositionProjection;
import com.csd.occ.ingestion.domain.RoutePositionReading;
import com.csd.occ.ingestion.domain.RoutePositionRepository;
import com.csd.occ.ingestion.domain.RoutePositionRoute;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgisRoutePositionRepository implements RoutePositionRepository {
    private static final String ROUTE_POSITION_QUERY = """
            WITH active_topology AS (
                SELECT id FROM ingestion.reference_dataset_version
                WHERE dataset = 'BUS_TOPOLOGY' AND status = 'ACTIVE'
            ), enabled_targets AS (
                SELECT source, scope_key, service_id
                FROM ingestion.poll_target
                WHERE source = 'DATAMALL' AND enabled AND service_id = ?
            ), enabled_target_counts AS (
                SELECT service_id, count(*) AS target_count
                FROM enabled_targets
                GROUP BY service_id
            ), completed_service_cycles AS (
                SELECT run.route_position_poll_cycle_id, target.service_id,
                       max(run.completed_at) AS completed_at
                FROM ingestion.poll_run run
                JOIN enabled_targets target
                  ON target.source = run.source AND target.scope_key = run.scope_key
                JOIN enabled_target_counts target_count ON target_count.service_id = target.service_id
                WHERE run.route_position_poll_cycle_id IS NOT NULL
                  AND run.completed_at IS NOT NULL
                  AND run.completed_at BETWEEN ? AND ?
                GROUP BY run.route_position_poll_cycle_id, target.service_id, target_count.target_count
                HAVING count(DISTINCT run.scope_key) = target_count.target_count
                   AND bool_and(coalesce(run.outcome IN ('SUCCESS', 'PARTIAL'), false))
            ), latest_completed_service_cycle AS (
                SELECT DISTINCT ON (service_id) route_position_poll_cycle_id, service_id, completed_at
                FROM completed_service_cycles
                ORDER BY service_id, completed_at DESC, route_position_poll_cycle_id
            ), completed_poll_runs AS (
                SELECT run.id, run.route_position_poll_cycle_id, cycle.service_id
                FROM ingestion.poll_run run
                JOIN completed_service_cycles cycle
                  ON cycle.route_position_poll_cycle_id = run.route_position_poll_cycle_id
                JOIN enabled_targets target
                  ON target.source = run.source
                 AND target.scope_key = run.scope_key
                 AND target.service_id = cycle.service_id
            ), route_points AS (
                SELECT route.reference_version_id, route.service_id, route.operator, route.direction_id,
                       route.stop_sequence, route.stop_code,
                       route.distance_km::double precision * 1000 AS reference_distance_m,
                       ST_Transform(stop.location, 3414) AS route_point,
                       lag(ST_Transform(stop.location, 3414)) OVER route_order AS previous_route_point,
                       lag(route.distance_km::double precision * 1000) OVER route_order
                           AS previous_reference_distance_m
                FROM ingestion.bus_route_stop route
                JOIN active_topology active ON active.id = route.reference_version_id
                JOIN ingestion.bus_stop stop
                  ON stop.reference_version_id = route.reference_version_id
                 AND stop.stop_code = route.stop_code
                WHERE route.service_id = ?
                WINDOW route_order AS (
                    PARTITION BY route.reference_version_id, route.service_id, route.operator, route.direction_id
                    ORDER BY route.stop_sequence
                )
            ), route_segments AS (
                SELECT reference_version_id, service_id, operator, direction_id,
                       stop_sequence AS segment_end_sequence,
                       ST_MakeLine(previous_route_point, route_point) AS segment_line,
                       ST_Distance(previous_route_point, route_point) AS segment_length_m,
                       previous_reference_distance_m, reference_distance_m
                FROM route_points
                WHERE previous_route_point IS NOT NULL
            ), route_segments_with_axis AS (
                SELECT segment.*,
                       coalesce(sum(segment_length_m) OVER (
                           PARTITION BY reference_version_id, service_id, operator, direction_id
                           ORDER BY segment_end_sequence
                           ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING
                       ), 0) AS segment_start_distance_m
                FROM route_segments segment
            ), route_metadata AS (
                SELECT reference_version_id, service_id, operator, direction_id,
                       ST_MakeLine(route_point ORDER BY stop_sequence) AS route_line,
                       (array_agg(stop_code ORDER BY stop_sequence))[1]
                           = (array_agg(stop_code ORDER BY stop_sequence DESC))[1] AS circular,
                       CASE WHEN bool_and(reference_distance_m IS NOT NULL)
                                      AND bool_and(previous_reference_distance_m IS NULL
                                          OR reference_distance_m >= previous_reference_distance_m)
                                      AND max(reference_distance_m) > min(reference_distance_m)
                            THEN max(reference_distance_m)
                       END AS reference_length_m
                FROM route_points
                GROUP BY reference_version_id, service_id, operator, direction_id
                HAVING count(*) >= 2
            ), eligible_observations AS (
                SELECT route.reference_version_id, route.service_id, route.operator, route.direction_id,
                       route.route_line, route.circular, route.reference_length_m,
                       selected_cycle.route_position_poll_cycle_id AS selected_poll_cycle_id,
                       observation.id AS observation_id, observation.raw_observation_id AS snapshot_id,
                       poll_run.route_position_poll_cycle_id AS poll_cycle_id, observation.stop_code,
                       target_stop.stop_sequence, observation.visit_order, observation.estimated_arrival_at,
                       observation.latitude::double precision AS latitude,
                       observation.longitude::double precision AS longitude,
                       observation.origin_code, observation.destination_code,
                       coalesce(observation.visit_number, 1) AS visit_number,
                       target_stop.reference_distances_m AS stop_reference_distances_m,
                       ST_Length(route.route_line) AS route_length_m,
                       CASE WHEN observation.id IS NULL THEN NULL ELSE
                           ST_LineLocatePoint(route.route_line,
                               ST_Transform(ST_SetSRID(ST_MakePoint(observation.longitude::double precision,
                                   observation.latitude::double precision), 4326), 3414))
                           * ST_Length(route.route_line)
                       END AS distance_along_route_m,
                       CASE WHEN observation.id IS NULL THEN NULL ELSE
                           ST_LineLocatePoint(route.route_line, target_stop.stop_point)
                           * ST_Length(route.route_line)
                       END AS stop_distance_along_route_m,
                       CASE WHEN observation.id IS NULL THEN NULL ELSE
                           ST_Transform(ST_SetSRID(ST_MakePoint(observation.longitude::double precision,
                               observation.latitude::double precision), 4326), 3414)
                       END AS observation_point,
                       observation.fetched_at
                FROM route_metadata route
                LEFT JOIN latest_completed_service_cycle selected_cycle
                  ON selected_cycle.service_id = route.service_id
                LEFT JOIN ingestion.bus_arrival_observation observation
                  ON observation.reference_version_id = route.reference_version_id
                 AND observation.service_id = route.service_id
                 AND (observation.operator IS NULL OR observation.operator = route.operator)
                 AND observation.classification = 'OBSERVATION_ONLY'
                 AND observation.latitude BETWEEN -90 AND 90
                 AND observation.longitude BETWEEN -180 AND 180
                 AND NOT (observation.latitude = 0 AND observation.longitude = 0)
                 AND observation.fetched_at BETWEEN ? AND ?
                 AND EXISTS (
                     SELECT 1 FROM completed_poll_runs completed_run
                     WHERE completed_run.id = observation.poll_run_id
                       AND completed_run.service_id = route.service_id
                 )
                 AND EXISTS (
                     SELECT 1 FROM route_points match
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
                         FROM route_points match
                         WHERE match.reference_version_id = route.reference_version_id
                           AND match.service_id = route.service_id
                           AND match.operator = route.operator
                           AND match.stop_code = observation.stop_code
                     ))
                 )
                LEFT JOIN LATERAL (
                    SELECT min(match.stop_sequence) AS stop_sequence,
                           (array_agg(match.route_point ORDER BY match.stop_sequence))[1] AS stop_point,
                           array_agg(match.reference_distance_m ORDER BY match.stop_sequence)
                               FILTER (WHERE match.reference_distance_m IS NOT NULL) AS reference_distances_m
                    FROM route_points match
                    WHERE match.reference_version_id = route.reference_version_id
                      AND match.service_id = route.service_id
                      AND match.operator = route.operator
                      AND match.direction_id = route.direction_id
                      AND match.stop_code = observation.stop_code
                ) target_stop ON observation.id IS NOT NULL
                LEFT JOIN ingestion.poll_run poll_run ON poll_run.id = observation.poll_run_id
            )
            SELECT observation.operator, observation.direction_id, observation.observation_id,
                   observation.selected_poll_cycle_id,
                   observation.snapshot_id, observation.poll_cycle_id, observation.stop_code,
                   observation.stop_sequence, observation.visit_order, observation.estimated_arrival_at,
                   observation.latitude, observation.longitude, observation.origin_code,
                   observation.destination_code, observation.visit_number,
                   observation.stop_reference_distances_m,
                   observation.route_length_m, observation.circular, observation.reference_length_m,
                   observation.distance_along_route_m, observation.stop_distance_along_route_m,
                   segment.segment_end_sequence,
                   CASE WHEN observation.observation_id IS NULL THEN NULL ELSE
                       segment.segment_start_distance_m
                       + ST_LineLocatePoint(segment.segment_line, observation.observation_point)
                           * segment.segment_length_m
                   END AS candidate_distance_along_route_m,
                   CASE WHEN observation.observation_id IS NULL
                                  OR segment.previous_reference_distance_m IS NULL
                                  OR segment.reference_distance_m IS NULL THEN NULL ELSE
                       segment.previous_reference_distance_m
                       + ST_LineLocatePoint(segment.segment_line, observation.observation_point)
                           * (segment.reference_distance_m - segment.previous_reference_distance_m)
                   END AS candidate_reference_distance_m,
                   CASE WHEN observation.observation_id IS NULL THEN NULL ELSE
                       ST_Distance(segment.segment_line, observation.observation_point)
                   END AS candidate_offset_m,
                   observation.fetched_at
            FROM eligible_observations observation
            LEFT JOIN route_segments_with_axis segment
              ON segment.reference_version_id = observation.reference_version_id
             AND segment.service_id = observation.service_id
             AND segment.operator = observation.operator
             AND segment.direction_id = observation.direction_id
             AND observation.observation_id IS NOT NULL
            ORDER BY observation.operator, observation.direction_id, observation.fetched_at,
                     observation.observation_id, segment.segment_end_sequence
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
            statement.setString(4, serviceId);
            statement.setTimestamp(5, Timestamp.from(from));
            statement.setTimestamp(6, Timestamp.from(to));
        }, (result, rowNumber) -> new RoutePositionRow(result.getString("operator"),
                result.getInt("direction_id"), result.getObject("selected_poll_cycle_id", UUID.class),
                result.getObject("observation_id", UUID.class),
                result.getObject("snapshot_id", UUID.class), result.getObject("poll_cycle_id", UUID.class),
                result.getString("stop_code"),
                result.getObject("stop_sequence") == null ? 0 : result.getInt("stop_sequence"),
                result.getObject("visit_order") == null ? 0 : result.getInt("visit_order"),
                result.getTimestamp("estimated_arrival_at") == null ? null
                        : result.getTimestamp("estimated_arrival_at").toInstant(),
                result.getObject("latitude") == null ? null : result.getDouble("latitude"),
                result.getObject("longitude") == null ? null : result.getDouble("longitude"),
                result.getString("origin_code"), result.getString("destination_code"),
                result.getObject("visit_number") == null ? 1 : result.getInt("visit_number"),
                doubleList(result.getArray("stop_reference_distances_m")), result.getDouble("route_length_m"),
                result.getBoolean("circular"),
                result.getObject("reference_length_m") == null ? null : result.getDouble("reference_length_m"),
                result.getObject("distance_along_route_m") == null ? null
                        : result.getDouble("distance_along_route_m"),
                result.getObject("stop_distance_along_route_m") == null ? null
                        : result.getDouble("stop_distance_along_route_m"),
                result.getObject("segment_end_sequence") == null ? null : result.getInt("segment_end_sequence"),
                result.getObject("candidate_distance_along_route_m") == null ? null
                        : result.getDouble("candidate_distance_along_route_m"),
                result.getObject("candidate_reference_distance_m") == null ? null
                        : result.getDouble("candidate_reference_distance_m"),
                result.getObject("candidate_offset_m") == null ? null : result.getDouble("candidate_offset_m"),
                result.getTimestamp("fetched_at") == null ? null : result.getTimestamp("fetched_at").toInstant()));

        Map<RouteKey, RouteBuilder> routes = new LinkedHashMap<>();
        for (RoutePositionRow row : rows) {
            RouteKey key = new RouteKey(row.operator(), row.directionId());
            RouteBuilder route = routes.computeIfAbsent(key,
                    ignored -> new RouteBuilder(row.routeLengthMeters(), row.circular(), row.referenceLengthMeters(),
                            row.selectedPollCycleId()));
            if (row.observationId() == null || row.distanceAlongRouteMeters() == null || row.fetchedAt() == null)
                continue;
            ReadingBuilder reading = route.readings().computeIfAbsent(row.observationId(),
                    ignored -> new ReadingBuilder(row));
            if (row.segmentEndSequence() != null && row.candidateDistanceAlongRouteMeters() != null
                    && row.candidateReferenceDistanceMeters() != null && row.candidateOffsetMeters() != null) {
                reading.projections().add(new RoutePositionProjection(row.segmentEndSequence(),
                        row.candidateDistanceAlongRouteMeters(), row.candidateReferenceDistanceMeters(),
                        row.candidateOffsetMeters()));
            }
        }
        return routes.entrySet().stream().map(entry -> entry.getValue().build(entry.getKey())).toList();
    }

    private static List<Double> doubleList(Array values) throws SQLException {
        if (values == null) return List.of();
        Object[] raw = (Object[]) values.getArray();
        List<Double> converted = new ArrayList<>(raw.length);
        for (Object value : raw) {
            if (value instanceof Number number) converted.add(number.doubleValue());
        }
        return List.copyOf(converted);
    }

    private record RouteKey(String operator, int directionId) {
    }

    private record RoutePositionRow(String operator, int directionId, UUID selectedPollCycleId,
                                    UUID observationId, UUID snapshotId,
                                    UUID pollCycleId, String stopCode, int stopSequence, int visitOrder,
                                    Instant estimatedArrivalAt, Double latitude, Double longitude,
                                    String originCode, String destinationCode, int visitNumber,
                                    List<Double> stopReferenceDistancesMeters, double routeLengthMeters,
                                    boolean circular, Double referenceLengthMeters, Double distanceAlongRouteMeters,
                                    Double stopDistanceAlongRouteMeters, Integer segmentEndSequence,
                                    Double candidateDistanceAlongRouteMeters, Double candidateReferenceDistanceMeters,
                                    Double candidateOffsetMeters, Instant fetchedAt) {
    }

    private static final class ReadingBuilder {
        private final RoutePositionRow row;
        private final List<RoutePositionProjection> projections = new ArrayList<>();

        private ReadingBuilder(RoutePositionRow row) {
            this.row = row;
        }

        private List<RoutePositionProjection> projections() { return projections; }

        private RoutePositionReading build() {
            return new RoutePositionReading(row.observationId(), row.snapshotId(), row.pollCycleId(), row.stopCode(),
                    row.stopSequence(), row.visitOrder(), row.distanceAlongRouteMeters(),
                    row.stopDistanceAlongRouteMeters(), row.latitude(), row.longitude(),
                    row.originCode(), row.destinationCode(), row.visitNumber(),
                    row.stopReferenceDistancesMeters(), projections, row.estimatedArrivalAt(), row.fetchedAt());
        }
    }

    private static final class RouteBuilder {
        private final double routeLengthMeters;
        private final boolean circular;
        private final Double referenceLengthMeters;
        private final UUID selectedPollCycleId;
        private final Map<UUID, ReadingBuilder> readings = new LinkedHashMap<>();

        private RouteBuilder(double routeLengthMeters, boolean circular, Double referenceLengthMeters,
                UUID selectedPollCycleId) {
            this.routeLengthMeters = routeLengthMeters;
            this.circular = circular;
            this.referenceLengthMeters = referenceLengthMeters;
            this.selectedPollCycleId = selectedPollCycleId;
        }

        private Map<UUID, ReadingBuilder> readings() { return readings; }

        private RoutePositionRoute build(RouteKey key) {
            return new RoutePositionRoute(key.operator(), key.directionId(), routeLengthMeters, circular,
                    referenceLengthMeters == null ? Double.NaN : referenceLengthMeters, selectedPollCycleId,
                    readings.values().stream().map(ReadingBuilder::build).toList());
        }
    }
}
