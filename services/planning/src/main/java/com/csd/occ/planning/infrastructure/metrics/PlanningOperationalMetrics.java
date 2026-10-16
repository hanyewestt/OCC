package com.csd.occ.planning.infrastructure.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public final class PlanningOperationalMetrics implements MeterBinder {
    private final JdbcTemplate jdbc;

    public PlanningOperationalMetrics(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("occ.planning.request.pending_count", this,
                        PlanningOperationalMetrics::pendingRequestCount)
                .description("Number of diagnosis requests queued, retryable, or processing")
                .register(registry);
        Gauge.builder("occ.planning.request.oldest_pending_age_seconds", this,
                        PlanningOperationalMetrics::oldestPendingRequestAgeSeconds)
                .description("Age of the oldest diagnosis request not yet completed")
                .register(registry);
        Gauge.builder("occ.planning.recommendation_job.pending_count", this,
                        PlanningOperationalMetrics::pendingRecommendationCount)
                .description("Number of recommendation jobs queued or processing")
                .register(registry);
        Gauge.builder("occ.planning.recommendation_job.oldest_pending_age_seconds", this,
                        PlanningOperationalMetrics::oldestPendingRecommendationAgeSeconds)
                .description("Age of the oldest recommendation job not yet delivered")
                .register(registry);
        Gauge.builder("occ.planning.model.fallback_record_count", this,
                        PlanningOperationalMetrics::fallbackRecordCount)
                .description("Retained recommendation model invocations that used deterministic fallback")
                .register(registry);
        Gauge.builder("occ.planning.model.provider_failure_record_count", this,
                        PlanningOperationalMetrics::providerFailureRecordCount)
                .description("Retained model invocation records that ended in provider failure")
                .register(registry);
    }

    private double pendingRequestCount() {
        return queryNumber("""
                SELECT COUNT(*) FROM planning.planning_request
                WHERE status IN ('PENDING', 'PROCESSING', 'FAILED_RETRYABLE')
                """);
    }

    private double oldestPendingRequestAgeSeconds() {
        return queryNumber("""
                SELECT COALESCE(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - MIN(created_at))), 0)
                FROM planning.planning_request
                WHERE status IN ('PENDING', 'PROCESSING', 'FAILED_RETRYABLE')
                """);
    }

    private double pendingRecommendationCount() {
        return queryNumber("""
                SELECT COUNT(*) FROM planning.recommendation_job
                WHERE status IN ('PENDING', 'PROCESSING')
                """);
    }

    private double oldestPendingRecommendationAgeSeconds() {
        return queryNumber("""
                SELECT COALESCE(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - MIN(created_at))), 0)
                FROM planning.recommendation_job
                WHERE status IN ('PENDING', 'PROCESSING')
                """);
    }

    private double fallbackRecordCount() {
        return queryNumber("""
                SELECT COUNT(*) FROM planning.model_invocation_record
                WHERE purpose = 'RECOMMENDATION' AND deterministic_fallback_used IS TRUE
                """);
    }

    private double providerFailureRecordCount() {
        return queryNumber("""
                SELECT COUNT(*) FROM planning.model_invocation_record
                WHERE outcome = 'PROVIDER_FAILURE'
                """);
    }

    private double queryNumber(String sql) {
        Number value = jdbc.queryForObject(sql, Number.class);
        return value == null ? 0 : value.doubleValue();
    }
}
