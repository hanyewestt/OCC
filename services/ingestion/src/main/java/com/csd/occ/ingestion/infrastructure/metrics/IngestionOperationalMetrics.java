package com.csd.occ.ingestion.infrastructure.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
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

    private double queryNumber(String sql) {
        Number value = jdbc.queryForObject(sql, Number.class);
        return value == null ? 0 : value.doubleValue();
    }
}
