package com.csd.occ.bunching.infrastructure.postgres;

import com.csd.occ.bunching.domain.BunchingAlertPolicy;
import com.csd.occ.bunching.domain.BunchingAlertPort;
import com.csd.occ.bunching.domain.BunchingEvaluation;
import com.csd.occ.bunching.domain.BunchingEvidencePort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Optional;
import java.util.Comparator;
import java.util.UUID;

/** Custom persistence is needed for per-group locking and transactional PostgreSQL notifications. */
@Component
public class PostgresBunchingAlertAdapter implements BunchingAlertPort {
    private final JdbcTemplate jdbc;
    private final BunchingEvidencePort evidence;
    private final Clock clock;
    private final BunchingAlertPolicy policy = new BunchingAlertPolicy();

    public PostgresBunchingAlertAdapter(JdbcTemplate jdbc, BunchingEvidencePort evidence, Clock clock) {
        this.jdbc = jdbc;
        this.evidence = evidence;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void trigger(BunchingEvaluation evaluation) {
        // Stable lock ordering avoids deadlocks when two snapshots share multiple groups.
        var groups = evaluation.groups().stream().filter(group -> group.group().directionId().isPresent())
                .sorted(Comparator.comparing((BunchingEvaluation.GroupEvaluation group) -> group.group().serviceId())
                        .thenComparing(group -> group.group().stopCode())
                        .thenComparingInt(group -> group.group().directionId().orElseThrow())).toList();
        for (var result : groups) {
            // Stale/future evidence must not advance the watermark or change a live alert.
            if (result.reason() == BunchingEvaluation.Reason.STALE_SNAPSHOT
                    || result.reason() == BunchingEvaluation.Reason.FUTURE_SNAPSHOT) continue;
            var group = result.group();
            var fetchedAt = Timestamp.from(evaluation.snapshot().fetchedAt());
            jdbc.update("""
                    INSERT INTO bunching.alert_state (service_id, stop_code, direction_id, last_fetched_at)
                    VALUES (?, ?, ?, '-infinity') ON CONFLICT DO NOTHING
                    """, group.serviceId(), group.stopCode(), group.directionId().orElseThrow());
            var previous = jdbc.queryForMap("""
                    SELECT active_alert_id, last_fetched_at FROM bunching.alert_state
                    WHERE service_id=? AND stop_code=? AND direction_id=? FOR UPDATE
                    """, group.serviceId(), group.stopCode(), group.directionId().orElseThrow());
            if (!fetchedAt.after((Timestamp) previous.get("last_fetched_at"))) continue;
            // Nullable SQL state means there is no active episode.
            UUID active = (UUID) previous.get("active_alert_id");
            var transition = policy.transition(result.state(), Optional.ofNullable(active));
            UUID episode = transition == BunchingAlertPolicy.Transition.OPENED ? UUID.randomUUID() : active;
            if (transition != BunchingAlertPolicy.Transition.NONE) {
                UUID event = UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO bunching.alert_event
                        (id, alert_id, action, service_id, stop_code, direction_id, raw_observation_id,
                         poll_run_id, fetched_at, triggered_at, evidence)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                        """, event, episode, transition.name(), group.serviceId(), group.stopCode(),
                        group.directionId().orElseThrow(), evaluation.snapshot().rawObservationId(),
                        evaluation.snapshot().pollRunId(), fetchedAt, Timestamp.from(clock.instant()),
                        evidence.encodeAlert(evaluation, result));
                // PostgreSQL delivers only after commit; rollback discards both event and notification.
                jdbc.queryForObject("SELECT pg_notify('bunching_alert', ?)", String.class, event.toString());
            }
            jdbc.update("""
                    UPDATE bunching.alert_state SET last_fetched_at=?, active_alert_id=?
                    WHERE service_id=? AND stop_code=? AND direction_id=?
                    """, fetchedAt, transition == BunchingAlertPolicy.Transition.CLEARED ? null : episode,
                    group.serviceId(), group.stopCode(), group.directionId().orElseThrow());
        }
    }
}
