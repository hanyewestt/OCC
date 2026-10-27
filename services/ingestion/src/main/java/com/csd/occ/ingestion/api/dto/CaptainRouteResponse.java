package com.csd.occ.ingestion.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Schema(description = "The signed-in captain's assigned simulation bus, its ordered route and its position. Not "
        + "paginated: one route is a single bounded resource the On Board Unit needs whole.")
public record CaptainRouteResponse(
        @Schema(description = "Always SIMULATION: only demo buses have a known identity and captain",
                allowableValues = {"SIMULATION"}, requiredMode = Schema.RequiredMode.REQUIRED) String classification,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Bus bus,
        @Schema(description = "Stops in route order", requiredMode = Schema.RequiredMode.REQUIRED) List<Stop> stops,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Position position) {

    @Schema(name = "CaptainRouteBus")
    public record Bus(
            @Schema(example = "demo-190-b", requiredMode = Schema.RequiredMode.REQUIRED) String busId,
            @Schema(example = "SBS5898U", requiredMode = Schema.RequiredMode.REQUIRED) String registrationNumber,
            @Schema(example = "190", requiredMode = Schema.RequiredMode.REQUIRED) String serviceId,
            @Schema(example = "1", requiredMode = Schema.RequiredMode.REQUIRED) int directionId) {}

    @Schema(name = "CaptainRouteStop")
    public record Stop(
            @Schema(example = "8", requiredMode = Schema.RequiredMode.REQUIRED) int sequence,
            @Schema(example = "44251", requiredMode = Schema.RequiredMode.REQUIRED) String stopCode,
            @Schema(example = "Bt Panjang Stn/Blk 604", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
            String description,
            @Schema(example = "Petir Rd", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED) String roadName,
            @Schema(description = "Distance along the route from its first stop, in kilometres", nullable = true,
                    example = "4.3", requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal distanceKm,
            @Schema(description = "When this bus should reach the stop (UTC); null for stops left behind or when "
                    + "no fresh progress exists", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
            Instant arrivalAt,
            @Schema(description = "PREDICTED is the simulation's prediction for the stop being approached; ESTIMATED is "
                    + "derived from route distance at the scenario's assumed speed",
                    allowableValues = {"PREDICTED", "ESTIMATED"}, nullable = true,
                    requiredMode = Schema.RequiredMode.REQUIRED) String arrivalKind) {}

    @Schema(name = "CaptainRoutePosition")
    public record Position(
            @Schema(description = "SIMULATION when fresh simulation progress places the bus; UNAVAILABLE otherwise",
                    allowableValues = {"SIMULATION", "UNAVAILABLE"}, requiredMode = Schema.RequiredMode.REQUIRED)
            String source,
            @Schema(description = "Route sequence of the stop most recently left", nullable = true, example = "7",
                    requiredMode = Schema.RequiredMode.REQUIRED) Integer lastStopSequence,
            @Schema(description = "Route sequence of the stop being approached", nullable = true, example = "8",
                    requiredMode = Schema.RequiredMode.REQUIRED) Integer nextStopSequence,
            @Schema(description = "When the progress evidence was fetched (UTC)", nullable = true,
                    requiredMode = Schema.RequiredMode.REQUIRED) Instant observedAt) {}
}
