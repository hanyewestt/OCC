package com.csd.occ.ingestion.api.dto;

import com.csd.occ.ingestion.domain.DemoFleetSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Simulation-only identities and immutable account assignments; unavailable progress is null.")
public record DemoFleetResponse(UUID demoRunId, String status, String classification, List<VehicleResponse> items,
                               long totalElements, int page, int size) {
    public static DemoFleetResponse from(DemoFleetSnapshot value) {
        return new DemoFleetResponse(value.demoRunId(), value.status(), "SIMULATION", value.items().stream()
                .map(bus -> new VehicleResponse(bus.busId(), bus.tripId(), bus.registrationNumber(), bus.serviceId(),
                        bus.directionId(), bus.captainUserId().orElse(null), bus.assignmentStatus(),
                        bus.progress().map(progress -> new ProgressResponse(progress.stopCode(), progress.stopSequence(),
                                progress.observedAt(), progress.assumedSpeedKmh(), progress.allowedSpeedReductionsKmh(),
                                progress.allowedDurationsSeconds())).orElse(null))).toList(),
                value.totalElements(), value.page(), value.size());
    }
    public record VehicleResponse(String busId, String tripId, String registrationNumber, String serviceId,
                                  int directionId, UUID captainUserId, String assignmentStatus, ProgressResponse progress) {}
    @Schema(description = "Synthetic progress and permitted demo speed advice; not live telemetry.")
    public record ProgressResponse(String stopCode, int stopSequence, Instant observedAt, int assumedSpeedKmh,
                                   List<Integer> allowedSpeedReductionsKmh, List<Integer> allowedDurationsSeconds) {}
}
