package com.csd.occ.ingestion.domain;

import java.time.Duration;

public record DemoReplayConfiguration(String scenario, long seed, Duration bunchingThreshold) {}
