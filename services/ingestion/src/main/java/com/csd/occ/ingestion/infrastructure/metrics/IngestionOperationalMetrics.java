package com.csd.occ.ingestion.infrastructure.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import com.csd.occ.ingestion.domain.HazardKind;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public final class IngestionOperationalMetrics implements MeterBinder {
    private final JdbcTemplate jdbc;

    public IngestionOperationalMetrics(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("occ.ingestion.feed.oldest_success_age_seconds", this,
                        IngestionOperationalMetrics::oldestSuccessfulFeedAgeSeconds)
                .description("Age of the oldest successful feed, or zero when no feed has succeeded")
                .register(registry);
        Gauge.builder("occ.ingestion.feed.unhealthy_count", this,
                        IngestionOperationalMetrics::unhealthyFeedCount)
                .description("Number of feed scopes that are degraded, failed, or have never run")
                .register(registry);
        Gauge.builder("occ.ingestion.arrival.feed_sequence", this,
                        IngestionOperationalMetrics::latestArrivalFeedSequence)
                .description("Highest durable bus-arrival feed sequence available to consumers")
                .register(registry);
        Gauge.builder("occ.ingestion.traffic_incident.latest_poll_success_age_seconds", this,
                        IngestionOperationalMetrics::latestTrafficIncidentSuccessAgeSeconds)
                .description("Age of the most recent successful DataMall traffic incident poll, or zero when none succeeded")
                .register(registry);
        Gauge.builder("occ.ingestion.traffic_incident.latest_poll_success", this,
                        IngestionOperationalMetrics::latestTrafficIncidentPollSuccess)
                .description("Whether the latest completed DataMall traffic incident poll succeeded")
                .register(registry);
        for (HazardKind feed : java.util.List.of(HazardKind.FLOOD, HazardKind.RAIL_DISRUPTION, HazardKind.ROADWORKS)) {
            Gauge.builder("occ.ingestion.hazard.latest_poll_outcome", this,
                            metrics -> metrics.latestHazardPollOutcome(feed))
                    .tag("feed", feed.name())
                    .description("Latest additional hazard poll: success=1, partial=0.5, failed=0, never completed=-1")
                    .register(registry);
            Gauge.builder("occ.ingestion.hazard.latest_poll_success_age_seconds", this,
                            metrics -> metrics.latestHazardSuccessAgeSeconds(feed))
                    .tag("feed", feed.name())
                    .description("Age of the most recent successful additional hazard poll, or zero when none succeeded")
                    .register(registry);
        }
    }

    private double oldestSuccessfulFeedAgeSeconds() {
        return queryNumber("""
                SELECT COALESCE(MAX(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - last_success_at))), 0)
                FROM ingestion.feed_status
                WHERE last_success_at IS NOT NULL
                """);
    }

    private double unhealthyFeedCount() {
        return queryNumber("""
                SELECT COUNT(*)
                FROM ingestion.feed_status
                WHERE status <> 'HEALTHY' OR last_success_at IS NULL
                """);
    }

    private double latestArrivalFeedSequence() {
        return queryNumber("""
                SELECT COALESCE(MAX(raw.feed_sequence), 0)
                FROM ingestion.raw_observation raw
                JOIN ingestion.poll_run run ON run.id = raw.poll_run_id
                WHERE raw.source = 'DATAMALL'
                  AND (raw.source_key LIKE 'stop:%' OR raw.source_key LIKE 'service:%:stop:%')
                  AND raw.classification = 'OBSERVATION_ONLY'
                  AND raw.http_status BETWEEN 200 AND 299
                  AND run.outcome = 'SUCCESS'
                """);
    }

    private double latestTrafficIncidentSuccessAgeSeconds() {
        return queryNumber("""
                SELECT COALESCE(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - MAX(completed_at))), 0)
                FROM ingestion.poll_run
                WHERE source = 'TRAFFIC_DATAMALL' AND outcome = 'SUCCESS' AND completed_at IS NOT NULL
                """);
    }

    private double latestTrafficIncidentPollSuccess() {
        return queryNumber("""
                SELECT COALESCE((
                    SELECT CASE WHEN outcome = 'SUCCESS' THEN 1 ELSE 0 END
                    FROM ingestion.poll_run
                    WHERE source = 'TRAFFIC_DATAMALL' AND completed_at IS NOT NULL
                    ORDER BY completed_at DESC, id DESC
                    LIMIT 1
                ), 0)
                """);
    }

    private double latestHazardPollOutcome(HazardKind feed) {
        var outcomes = jdbc.query("""
                SELECT outcome FROM ingestion.poll_run
                WHERE source = ? AND completed_at IS NOT NULL
                ORDER BY completed_at DESC, id DESC LIMIT 1
                """, (rs, row) -> rs.getString(1), feed.name() + "_DATAMALL");
        if (outcomes.isEmpty()) return -1;
        return switch (outcomes.getFirst()) {
            case "SUCCESS" -> 1;
            case "PARTIAL" -> 0.5;
            default -> 0;
        };
    }

    private double latestHazardSuccessAgeSeconds(HazardKind feed) {
        var value = jdbc.queryForObject("""
                SELECT COALESCE(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - MAX(completed_at))), 0)
                FROM ingestion.poll_run
                WHERE source = ? AND outcome = 'SUCCESS' AND completed_at IS NOT NULL
                """, Number.class, feed.name() + "_DATAMALL");
        return value == null ? 0 : value.doubleValue();
    }

    private double queryNumber(String sql) {
        Number value = jdbc.queryForObject(sql, Number.class);
        return value == null ? 0 : value.doubleValue();
    }
}
