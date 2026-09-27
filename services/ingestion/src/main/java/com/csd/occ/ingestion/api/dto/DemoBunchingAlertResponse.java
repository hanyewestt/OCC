package com.csd.occ.ingestion.api.dto;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Bunching transition detected from simulation observations")
public record DemoBunchingAlertResponse(UUID eventId, UUID bunchingEventId, String action, String classification, String serviceId, String stopCode,
                                        String stopName, String stopRoadName, int directionId, Instant detectedAt,
                                        @Schema(description = "Absolute predicted-arrival gap between the bunched buses, in seconds", nullable = true)
                                        Long headwaySeconds, DemoVehicleResponse leadingVehicle,
                                        DemoVehicleResponse followingVehicle) {}
