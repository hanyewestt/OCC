package com.csd.occ.ingestion.infrastructure.postgres;

import com.csd.occ.ingestion.domain.ArrivalFetch;
import com.csd.occ.ingestion.domain.ArrivalPoll;
import com.csd.occ.ingestion.domain.ArrivalPollTarget;
import com.csd.occ.ingestion.domain.ArrivalTelemetryPort;
import com.csd.occ.ingestion.infrastructure.config.ArrivalPollingProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

@Component
public class PostgresArrivalTelemetryAdapter implements ArrivalTelemetryPort {
    private final JdbcTemplate jdbc;
    private final ArrivalPollingProperties properties;

    public PostgresArrivalTelemetryAdapter(JdbcTemplate jdbc, ArrivalPollingProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override
    public List<ArrivalPollTarget> targets() {
        return jdbc.query("""
                SELECT source, scope_key, stop_code FROM ingestion.poll_target
                WHERE enabled AND source = 'DATAMALL' AND stop_code ~ '^[0-9]{5}$'
                  AND scope_key = 'stop:' || stop_code ORDER BY scope_key
                """, (row, index) -> new ArrivalPollTarget(row.getString(1), row.getString(2), row.getString(3)));
    }

    @Override
    @Transactional
    public Optional<ArrivalPoll> claim(ArrivalPollTarget target, Instant now, Duration lease) {
        // Serialize claims per target across instances; no database transaction spans the HTTP call.
        boolean locked = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))", Boolean.class,
                target.source() + ":" + target.scopeKey()));
        if (!locked) return Optional.empty();
        var running = jdbc.query("""
                SELECT id, started_at, lease_expires_at FROM ingestion.poll_run
                WHERE source = ? AND scope_key = ? AND outcome = 'RUNNING' FOR UPDATE
                """, (row, index) -> new ArrivalPoll(row.getObject(1, UUID.class), target,
                row.getTimestamp(2).toInstant(), row.getTimestamp(3) == null
                ? row.getTimestamp(2).toInstant().plus(lease) : row.getTimestamp(3).toInstant()),
                target.source(), target.scopeKey());
        for (var poll : running) {
            if (!now.isBefore(poll.leaseExpiresAt())) {
                finish(poll, expired(), now, properties.staleAfterSeconds());
            }
        }
        if (running.stream().anyMatch(poll -> now.isBefore(poll.leaseExpiresAt()))) return Optional.empty();
        var poll = new ArrivalPoll(UUID.randomUUID(), target, now, now.plus(lease));
        int inserted = jdbc.update("""
                INSERT INTO ingestion.poll_run
                    (id, source, scope_key, idempotency_key, started_at, lease_expires_at, outcome, records_received)
                VALUES (?, ?, ?, ?, ?, ?, 'RUNNING', 0) ON CONFLICT (idempotency_key) DO NOTHING
                """, poll.id(), target.source(), target.scopeKey(),
                target.source() + "-" + target.scopeKey() + "-" + now.getEpochSecond() / 60,
                Timestamp.from(now), Timestamp.from(poll.leaseExpiresAt()));
        if (inserted == 0) return Optional.empty();
        jdbc.update("""
                INSERT INTO ingestion.telemetry_audit
                (id, poll_run_id, actor, action, source, scope_key, occurred_at, outcome, classification, records_received)
                VALUES (?, ?, 'SYSTEM:INGESTION', 'POLL_STARTED', ?, ?, ?, 'RUNNING', 'OBSERVATION_ONLY', 0)
                """, UUID.randomUUID(), poll.id(), target.source(), target.scopeKey(), Timestamp.from(now));
        return Optional.of(poll);
    }

    @Override
    public List<UUID> expiredIds(Instant now, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Recovery limit must be 1..1000");
        return jdbc.queryForList("""
                SELECT id FROM ingestion.poll_run WHERE outcome='RUNNING' AND source='DATAMALL'
                  AND scope_key ~ '^stop:[0-9]{5}$'
                  AND (lease_expires_at <= ? OR (lease_expires_at IS NULL AND started_at <= ?))
                ORDER BY started_at, id LIMIT ?
                """, UUID.class, Timestamp.from(now), Timestamp.from(now.minus(properties.lease())), limit);
    }

    @Override
    @Transactional
    public boolean recoverExpired(UUID pollRunId, Instant now) {
        // Read poll metadata directly: disabled/deleted targets must not strand their audit trail.
        var expired = jdbc.query("""
                SELECT id, source, scope_key, started_at, lease_expires_at FROM ingestion.poll_run
                WHERE id=? AND outcome='RUNNING' AND source='DATAMALL' AND scope_key ~ '^stop:[0-9]{5}$'
                  AND (lease_expires_at <= ? OR (lease_expires_at IS NULL AND started_at <= ?))
                FOR UPDATE SKIP LOCKED
                """, (row, index) -> new ArrivalPoll(row.getObject("id", UUID.class),
                new ArrivalPollTarget(row.getString("source"), row.getString("scope_key"), row.getString("scope_key").substring(5)),
                row.getTimestamp("started_at").toInstant(), row.getTimestamp("lease_expires_at") == null
                ? row.getTimestamp("started_at").toInstant().plus(properties.lease()) : row.getTimestamp("lease_expires_at").toInstant()),
                pollRunId, Timestamp.from(now), Timestamp.from(now.minus(properties.lease())));
        if (expired.isEmpty()) return false;
        finish(expired.getFirst(), expired(), now, properties.staleAfterSeconds());
        return true;
    }

    @Override
    @Transactional
    public boolean complete(ArrivalPoll poll, ArrivalFetch fetch, Instant fetchedAt, int staleAfterSeconds) {
        var outcomes = jdbc.queryForList("SELECT outcome FROM ingestion.poll_run WHERE id = ? FOR UPDATE",
                String.class, poll.id());
        if (outcomes.isEmpty() || !"RUNNING".equals(outcomes.getFirst())) return false;
        if (!fetchedAt.isBefore(poll.leaseExpiresAt())) {
            finish(poll, expired(), fetchedAt, staleAfterSeconds);
            return false;
        }
        finish(poll, fetch, fetchedAt, staleAfterSeconds);
        return true;
    }

    private void finish(ArrivalPoll poll, ArrivalFetch fetch, Instant now, int staleAfterSeconds) {
        Optional<UUID> rawId = Optional.empty();
        if (fetch.isSuccessful()) {
            rawId = Optional.of(UUID.randomUUID());
            jdbc.update("""
                    INSERT INTO ingestion.raw_observation
                    (id, poll_run_id, source, source_key, request_parameters, payload, payload_checksum,
                     fetched_at, ingested_at, http_status, classification)
                    VALUES (?, ?, ?, ?, jsonb_build_object('BusStopCode', CAST(? AS text)), CAST(? AS jsonb),
                            ?, ?, ?, ?, 'OBSERVATION_ONLY')
                    """, rawId.orElseThrow(), poll.id(), poll.target().source(), poll.target().scopeKey(),
                    poll.target().stopCode(), fetch.payload().orElseThrow(), fetch.checksum().orElseThrow(),
                    Timestamp.from(now), Timestamp.from(now), fetch.httpStatus().orElseThrow());
        }
        String outcome = fetch.isSuccessful() ? "SUCCESS" : "FAILED";
        jdbc.update("""
                UPDATE ingestion.poll_run SET completed_at = ?, outcome = ?, http_status = ?,
                    records_received = ?, error_code = ?, error_message = NULL WHERE id = ?
                """, Timestamp.from(now), outcome, status(fetch), fetch.recordsReceived(), reason(fetch), poll.id());
        jdbc.update("""
                INSERT INTO ingestion.feed_status
                (source, scope_key, status, last_attempt_at, last_success_at, consecutive_failures,
                 stale_after_seconds, message, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (source, scope_key) DO UPDATE SET
                    status = CASE WHEN EXCLUDED.status = 'HEALTHY' THEN 'HEALTHY'
                                  WHEN ingestion.feed_status.last_success_at IS NULL THEN 'FAILED' ELSE 'DEGRADED' END,
                    last_attempt_at = EXCLUDED.last_attempt_at,
                    last_success_at = COALESCE(EXCLUDED.last_success_at, ingestion.feed_status.last_success_at),
                    consecutive_failures = CASE WHEN EXCLUDED.status = 'HEALTHY' THEN 0
                                               ELSE ingestion.feed_status.consecutive_failures + 1 END,
                    stale_after_seconds = EXCLUDED.stale_after_seconds, message = EXCLUDED.message,
                    updated_at = EXCLUDED.updated_at
                WHERE ingestion.feed_status.last_attempt_at IS NULL
                   OR ingestion.feed_status.last_attempt_at <= EXCLUDED.last_attempt_at
                """, poll.target().source(), poll.target().scopeKey(), fetch.isSuccessful() ? "HEALTHY" : "FAILED",
                Timestamp.from(poll.startedAt()), fetch.isSuccessful() ? Timestamp.from(now) : null,
                fetch.isSuccessful() ? 0 : 1, staleAfterSeconds, reason(fetch), Timestamp.from(now));
        audit(poll, "POLL_COMPLETED", outcome, now, rawId, fetch);
    }

    private void audit(ArrivalPoll poll, String action, String outcome, Instant now, Optional<UUID> rawId, ArrivalFetch fetch) {
        jdbc.update("""
                INSERT INTO ingestion.telemetry_audit
                (id, poll_run_id, actor, action, source, scope_key, occurred_at, outcome, classification,
                 raw_observation_id, payload_checksum, http_status, records_received, reason)
                VALUES (?, ?, 'SYSTEM:INGESTION', ?, ?, ?, ?, ?, 'OBSERVATION_ONLY', ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), poll.id(), action, poll.target().source(), poll.target().scopeKey(),
                Timestamp.from(now), outcome, rawId.orElse(null), fetch.checksum().orElse(null),
                status(fetch), fetch.recordsReceived(), reason(fetch));
    }

    private ArrivalFetch expired() {
        return new ArrivalFetch(OptionalInt.empty(), Optional.empty(), Optional.empty(), 0, ArrivalFetch.Reason.LEASE_EXPIRED);
    }

    // JDBC nulls represent deliberately absent database diagnostics, never domain values.
    private Integer status(ArrivalFetch fetch) { return fetch.httpStatus().isPresent() ? fetch.httpStatus().getAsInt() : null; }
    private String reason(ArrivalFetch fetch) { return fetch.reason() == ArrivalFetch.Reason.NONE ? null : fetch.reason().name(); }
}
