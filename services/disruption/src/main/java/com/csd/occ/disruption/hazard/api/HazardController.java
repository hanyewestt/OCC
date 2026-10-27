package com.csd.occ.disruption.hazard.api;

import com.csd.occ.disruption.hazard.api.dto.HazardResponse;
import com.csd.occ.disruption.hazard.domain.HazardReadService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
public class HazardController {
    private final HazardReadService hazards;
    public HazardController(HazardReadService hazards) { this.hazards = hazards; }
    @GetMapping("/api/v1/disruption/hazards")
    @Operation(operationId = "listHazards", summary = "List current hazards and candidate synthetic bus exposure")
    public HazardResponse.PageResponse list(@RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var result = hazards.list(kind, page, size);
        return new HazardResponse.PageResponse(result.items().stream().map(HazardResponse::from).toList(), page, size,
                result.totalElements(), result.feeds());
    }
    @GetMapping("/api/v1/disruption/hazards/{id}")
    @Operation(operationId = "getHazard", summary = "Read a hazard with evidence and candidate exposure")
    public HazardResponse find(@PathVariable UUID id) { return HazardResponse.from(hazards.find(id)); }
    @GetMapping("/internal/v1/disruption/hazards/{id}")
    @Operation(operationId = "getPlanningHazard", summary = "Read current hazard evidence for Planning peer validation")
    public HazardResponse internal(@PathVariable UUID id) { return HazardResponse.from(hazards.find(id)); }
}
