package com.csd.occ.ingestion.api;

import com.csd.occ.ingestion.api.dto.ActiveDemoAssignmentsResponse;
import com.csd.occ.ingestion.domain.AdminDemoAssignmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Admin demo assignments")
public class AdminDemoAssignmentController {
    private final AdminDemoAssignmentService assignments;
    public AdminDemoAssignmentController(AdminDemoAssignmentService assignments) { this.assignments = assignments; }

    @GetMapping("/api/v1/admin/demo-runs/active/assignments")
    @Operation(operationId = "getAdminActiveDemoAssignments", summary = "List active demo bus assignments and pending operations",
            description = "ADMIN only. Combines the current Ingestion assignment snapshot with one Planning pending-operation lookup. "
                    + "Returns 503 if Planning coordination status cannot be established.")
    @ApiResponse(responseCode = "200", description = "Current active demo assignments, or an empty list when no run is active")
    @ApiResponse(responseCode = "401", description = "Missing or invalid authentication")
    @ApiResponse(responseCode = "403", description = "ADMIN role required")
    @ApiResponse(responseCode = "503", description = "Planning assignment status unavailable")
    public ResponseEntity<ActiveDemoAssignmentsResponse> active() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ActiveDemoAssignmentsResponse.from(assignments.active()));
    }
}
