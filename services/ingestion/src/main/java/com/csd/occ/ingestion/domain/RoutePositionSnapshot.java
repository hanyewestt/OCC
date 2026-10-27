package com.csd.occ.ingestion.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public record RoutePositionSnapshot(String serviceId, Instant computedAt,
                                    Optional<UUID> sourcePollCycleId, Optional<Instant> sourceObservedAt,
                                    long refreshIntervalSeconds, long maxSourceAgeSeconds,
                                    List<RoutePositionDirection> directions) {
}
