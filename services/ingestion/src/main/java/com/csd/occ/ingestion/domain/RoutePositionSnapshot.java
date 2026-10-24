package com.csd.occ.ingestion.domain;

import java.time.Instant;
import java.util.List;

public record RoutePositionSnapshot(String serviceId, Instant computedAt, long refreshIntervalSeconds,
                                    List<RoutePositionDirection> directions) {
}
