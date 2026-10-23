package com.csd.occ.ingestion.infrastructure.config;

import com.csd.occ.ingestion.domain.RoutePositionSettings;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class ConfiguredRoutePositionSettings implements RoutePositionSettings {
    private final RoutePositionProperties positions;
    private final IngestionScheduleProperties schedule;

    public ConfiguredRoutePositionSettings(RoutePositionProperties positions, IngestionScheduleProperties schedule) {
        this.positions = positions;
        this.schedule = schedule;
    }

    @Override
    public Duration pollInterval() { return schedule.busArrivalPollInterval(); }

    @Override
    public int maxPollAgeCycles() { return positions.maxPollAgeCycles(); }

    @Override
    public double clusterToleranceMeters() { return positions.clusterToleranceMeters(); }

    @Override
    public double speedCapMetersPerSecond() { return positions.speedCapMetersPerSecond(); }
}
