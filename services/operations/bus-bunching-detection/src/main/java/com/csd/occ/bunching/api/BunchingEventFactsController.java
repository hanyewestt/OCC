package com.csd.occ.bunching.api;

import com.csd.occ.bunching.api.dto.BunchingEventFactsResponse;
import com.csd.occ.bunching.domain.AlertEvent;
import com.csd.occ.bunching.domain.AlertEventRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/v1/bunching-alert-events")
@Tag(name = "Bunching event facts (internal)")
public class BunchingEventFactsController {
    private final AlertEventRepository events;

    public BunchingEventFactsController(AlertEventRepository events) { this.events = events; }

    @GetMapping("/{eventId}")
    @Operation(operationId = "getCanonicalBunchingEventFacts", summary = "Get immutable detection facts for RCA")
    @ApiResponse(responseCode = "200", description = "Canonical WQAP-2 event facts")
    @ApiResponse(responseCode = "404", description = "Event not found")
    public BunchingEventFactsResponse find(@PathVariable UUID eventId) {
        AlertEvent event = events.findById(eventId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Bunching event not found"));
        List<Integer> observed = java.util.stream.Stream.of(event.firstObservedHeadwaySeconds(),
                event.secondObservedHeadwaySeconds()).filter(java.util.Objects::nonNull).toList();
        List<java.time.Instant> times = java.util.stream.Stream.of(event.firstConfirmingAt(), event.secondConfirmingAt())
                .filter(java.util.Objects::nonNull).toList();
        return new BunchingEventFactsResponse(event.id(), event.alertId(), event.action(), event.classification(),
                event.demoRunId(), event.serviceId(), event.directionId(), event.stopCode(), event.leadingObservationId(),
                event.followingObservationId(), event.confirmingObservationIds(),
                "OPENED".equals(event.action()) ? "CONFIRMED" : "CLEARED", event.expectedHeadwaySeconds(), observed,
                event.triggeredAt(), times, event.detectionEvidenceIds(), event.detectionRuleVersion());
    }
}
