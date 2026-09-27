package com.csd.occ.ingestion.api;

import com.csd.occ.ingestion.api.dto.DemoModeRequest;
import com.csd.occ.ingestion.api.dto.DemoModeResponse;
import com.csd.occ.ingestion.domain.DemoModeService;
import com.csd.occ.ingestion.domain.DemoRunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "occ.ingestion.demo.enabled", havingValue = "true")
@RequestMapping(value = "/api/v1/demo-mode", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Demo mode")
public class DemoModeController {
    private final DemoModeService mode;
    private final DemoRunService runs;

    public DemoModeController(DemoModeService mode, DemoRunService runs) { this.mode = mode; this.runs = runs; }

    @GetMapping
    @Operation(operationId = "getDemoMode", summary = "Read the persisted runtime demo setting")
    public DemoModeResponse current() { return new DemoModeResponse(mode.enabled()); }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "setDemoMode", summary = "Start or stop simulation bunching without restarting ingestion",
            description = "Enabling starts or resumes the active simulation run. Disabling switches bunching to the live feed and closes the active run.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Runtime demo mode changed"),
            @ApiResponse(responseCode = "400", description = "The enabled field is required"),
            @ApiResponse(responseCode = "409", description = "Another demo run is active"),
            @ApiResponse(responseCode = "503", description = "Bunching control is unavailable")})
    public DemoModeResponse set(@Valid @RequestBody DemoModeRequest request) {
        return new DemoModeResponse(runs.setMode(Boolean.TRUE.equals(request.enabled())));
    }
}
