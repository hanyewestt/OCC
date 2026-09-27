package com.csd.occ.ingestion.domain;

import java.time.Instant;

/** Vehicle enrichment may be absent; never suppresses a detection event from the demo list. */
public record DemoAlertDetails(String action, String classification, String serviceId, String stopCode,
                               String stopName, String stopRoadName, int directionId, Instant detectedAt,
                               Long headwaySeconds, DemoVehicleDetails leadingVehicle,
                               DemoVehicleDetails followingVehicle) {}
