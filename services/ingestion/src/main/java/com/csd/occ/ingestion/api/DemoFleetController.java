package com.csd.occ.ingestion.api;

import com.csd.occ.ingestion.api.dto.DemoFleetResponse;
import com.csd.occ.ingestion.domain.DemoFleetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Demo fleet")
public class DemoFleetController {
    private final DemoFleetService fleet;
    public DemoFleetController(DemoFleetService fleet) { this.fleet = fleet; }

    @GetMapping("/internal/v1/ingestion/demo-fleet")
    @Operation(operationId = "getDemoFleet", summary = "Read current simulation assignments and progress",
            description = "Requires the Planning or Disruption peer capability. Closed/preparing runs return no current buses. "
                    + "page defaults to 0; size defaults to 20 and is bounded to 1–100; sorted by busId.")
    @ApiResponse(responseCode = "200", description = "Simulation fleet page")
    @ApiResponse(responseCode = "400", description = "Invalid pagination or run identity")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Peer capability required")
    @ApiResponse(responseCode = "404", description = "Run not found")
    public DemoFleetResponse find(@RequestParam UUID demoRunId, @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "20") int size) {
        return DemoFleetResponse.from(fleet.find(demoRunId, page, size));
    }
}
