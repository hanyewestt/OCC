package com.csd.occ.ingestion.domain;

import java.time.Instant;
import java.util.UUID;

public record ArrivalPoll(UUID id, ArrivalPollTarget target, Instant startedAt, Instant leaseExpiresAt) {}
