package com.csd.occ.ingestion.api;

import com.csd.occ.ingestion.api.dto.CaptainRouteResponse;
import com.csd.occ.ingestion.api.dto.PacingGapResponse;
import com.csd.occ.ingestion.domain.CaptainRoute;
import com.csd.occ.ingestion.domain.CaptainRouteService;
import com.csd.occ.ingestion.domain.PacingGap;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/captain-routes", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Captain route", description = "The signed-in captain's assigned simulation bus and its route. CAPTAIN only.")
public class CaptainRouteController {
    private final CaptainRouteService routes;

    public CaptainRouteController(CaptainRouteService routes) {
        this.routes = routes;
    }

    @GetMapping("/current")
    @Operation(operationId = "getCurrentCaptainRoute", summary = "Get the captain's assigned bus route and position",
            description = "The captain is the authenticated token subject, matched to the bus assigned to that account "
                    + "in the current simulation run. Readable by CAPTAIN only.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Assigned bus with its route",
                    content = @Content(schema = @Schema(implementation = CaptainRouteResponse.class))),
            @ApiResponse(responseCode = "204", description = "No bus is assigned to this captain in a current run",
                    content = @Content),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token", content = @Content),
            @ApiResponse(responseCode = "403", description = "Role is not CAPTAIN", content = @Content)})
    public ResponseEntity<CaptainRouteResponse> current(Authentication captain) {
        return routes.current(captain.getName()).map(CaptainRouteController::response).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    private static CaptainRouteResponse response(CaptainRoute route) {
        var position = route.position();
        return new CaptainRouteResponse("SIMULATION",
                new CaptainRouteResponse.Bus(route.busId(), route.registrationNumber(), route.serviceId(),
                        route.directionId()),
                route.stops().stream().map(item -> new CaptainRouteResponse.Stop(item.stop().stopSequence(),
                        item.stop().stopCode(), item.stop().description(), item.stop().roadName(),
                        item.stop().distanceKm(), item.arrivalAt().orElse(null),
                        item.arrivalKind().map(Enum::name).orElse(null))).toList(),
                new CaptainRouteResponse.Position(position.source().name(), position.lastStopSequence().orElse(null),
                        position.nextStopSequence().orElse(null), position.observedAt().orElse(null)),
                route.pacing().map(pacing -> new CaptainRouteResponse.Pacing(
                        pacing.metric().targetHeadway().toSeconds(),
                        pacing.metric().ahead().map(CaptainRouteController::gap).orElse(null),
                        pacing.metric().behind().map(CaptainRouteController::gap).orElse(null),
                        pacing.metric().observedAt(), pacing.calculatedAt())).orElse(null));
    }

    private static PacingGapResponse gap(PacingGap gap) {
        return new PacingGapResponse(gap.headway().toSeconds(), gap.state().name());
    }
}
