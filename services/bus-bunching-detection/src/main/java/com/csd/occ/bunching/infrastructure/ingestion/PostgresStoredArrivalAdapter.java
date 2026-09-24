package com.csd.occ.bunching.infrastructure.ingestion;

import com.csd.occ.bunching.domain.ArrivalSnapshot;
import com.csd.occ.bunching.domain.StoredArrivalPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class PostgresStoredArrivalAdapter implements StoredArrivalPort {
    private final JdbcTemplate jdbc;
    private final StoredArrivalMapper mapper;

    public PostgresStoredArrivalAdapter(JdbcTemplate jdbc, StoredArrivalMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public List<UUID> pendingIds(int limit, Instant now) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Limit must be 1..1000");
        // No high-water timestamp: late commits and process restarts cannot skip unrecorded rows.
        return jdbc.query("""
                SELECT input.id FROM ingestion.bunching_snapshot input
                LEFT JOIN bunching.source_retry retry ON retry.raw_observation_id = input.id
                WHERE NOT EXISTS (SELECT 1 FROM bunching.bunching_observation done WHERE done.raw_observation_id = input.id)
                  AND NOT EXISTS (SELECT 1 FROM bunching.source_rejection rejected WHERE rejected.raw_observation_id = input.id)
                  AND (retry.next_attempt_at IS NULL OR retry.next_attempt_at <= ?)
                ORDER BY COALESCE(retry.next_attempt_at, input.fetched_at), input.fetched_at, input.id LIMIT ?
                """, (row, index) -> row.getObject("id", UUID.class), Timestamp.from(now), limit);
    }

    @Override
    public ArrivalSnapshot snapshot(UUID id) {
        var source = jdbc.queryForObject("""
                SELECT id, poll_run_id, fetched_at, source_key, payload::text, normalized_arrivals::text
                FROM ingestion.bunching_snapshot WHERE id = ?
                """, (row, index) -> new StoredArrivalMapper.StoredSnapshot(row.getObject("id", UUID.class),
                row.getObject("poll_run_id", UUID.class), row.getTimestamp("fetched_at").toInstant(),
                row.getString("source_key"), row.getString("payload"), row.getString("normalized_arrivals")), id);
        if (source == null) throw new IllegalStateException("Source observation is no longer available");
        var routes = jdbc.query("""
                SELECT * FROM ingestion.bunching_route_direction WHERE stop_code = ?
                """, (row, index) -> new StoredArrivalMapper.RouteDirection(
                row.getObject("reference_version_id", UUID.class), row.getString("service_id"),
                row.getString("operator"), row.getInt("direction_id"), row.getString("origin_code"),
                row.getString("destination_code"), row.getString("stop_code"), row.getInt("stop_visits")),
                source.sourceKey().substring("stop:".length()));
        return mapper.map(source, routes);
    }

    @Override
    @Transactional
    public void reject(UUID id, Instant rejectedAt, String reason) {
        int inserted = jdbc.update("""
                INSERT INTO bunching.source_rejection (raw_observation_id, rejected_at, reason)
                VALUES (?, ?, ?) ON CONFLICT (raw_observation_id) DO NOTHING
                """, id, Timestamp.from(rejectedAt), reason);
        if (inserted == 1) audit(id, rejectedAt, "SOURCE_REJECTED", reason);
    }

    @Override
    @Transactional
    public void defer(UUID id, Instant failedAt, Instant nextAttemptAt, RetryReason reason) {
        jdbc.update("""
                INSERT INTO bunching.source_retry (raw_observation_id, next_attempt_at)
                VALUES (?, ?)
                ON CONFLICT (raw_observation_id) DO UPDATE
                SET next_attempt_at = GREATEST(bunching.source_retry.next_attempt_at, EXCLUDED.next_attempt_at)
                """, id, Timestamp.from(nextAttemptAt));
        audit(id, failedAt, "SOURCE_DEFERRED", reason.name());
    }

    private void audit(UUID id, Instant occurredAt, String action, String reason) {
        // Copy provenance before telemetry retention; never copy provider payloads or exception text.
        jdbc.update("""
                INSERT INTO bunching.source_processing_audit
                (id, raw_observation_id, poll_run_id, source_key, fetched_at, actor, action, occurred_at,
                 reason, next_attempt_at, classification)
                SELECT ?, ?, input.poll_run_id, input.source_key, input.fetched_at,
                       'SYSTEM:BUS_BUNCHING_DETECTION', ?, ?, ?,
                       CASE WHEN ? = 'SOURCE_DEFERRED' THEN retry.next_attempt_at ELSE NULL END, 'OBSERVATION_ONLY'
                FROM (VALUES (CAST(? AS uuid))) source(id)
                LEFT JOIN ingestion.bunching_snapshot input ON input.id = source.id
                LEFT JOIN bunching.source_retry retry ON retry.raw_observation_id = source.id
                """, UUID.randomUUID(), id, action, Timestamp.from(occurredAt), reason, action, id);
    }
}
