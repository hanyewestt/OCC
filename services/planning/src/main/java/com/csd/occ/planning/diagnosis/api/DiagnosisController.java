package com.csd.occ.planning.diagnosis.api;

import com.csd.occ.planning.diagnosis.api.dto.DiagnosisResponse;
import com.csd.occ.planning.diagnosis.domain.DiagnosisQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ProblemDetail;

@RestController
@RequestMapping("/api/v1/planning/diagnoses")
@Tag(name = "Root-cause diagnoses")
public class DiagnosisController {
    private final DiagnosisQueryService diagnoses;
    private final com.csd.occ.planning.diagnosis.domain.PlanningScopeService scopes;

    public DiagnosisController(DiagnosisQueryService diagnoses, com.csd.occ.planning.diagnosis.domain.PlanningScopeService scopes) {
        this.diagnoses = diagnoses; this.scopes = scopes;
    }

    @GetMapping
    @Operation(operationId = "listActivePlanningDiagnoses", summary = "List live diagnoses or diagnoses for the active simulation run")
    @ApiResponse(responseCode = "200", description = "Scoped diagnosis lifecycle and results, newest first")
    @ApiResponse(responseCode = "400", description = "Invalid page bounds")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "ADMIN or CONTROLLER role required")
    @ApiResponse(responseCode = "503", description = "Shared data selection is unavailable")
    public com.csd.occ.planning.diagnosis.api.dto.PlanningDiagnosisPageResponse list(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "50") int size) {
        return com.csd.occ.planning.diagnosis.api.dto.PlanningDiagnosisPageResponse.from(scopes.list(page, size));
    }

    @GetMapping("/hazard-requests")
    @Operation(operationId = "listActiveHazardRequests", summary = "List pending and failed hazard processing in the active scope")
    @ApiResponse(responseCode = "200", description = "Latest unfinished hazard request per disruption, newest first")
    @ApiResponse(responseCode = "400", description = "Invalid page bounds")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "ADMIN or CONTROLLER role required")
    @ApiResponse(responseCode = "503", description = "Shared data selection is unavailable")
    public com.csd.occ.planning.diagnosis.api.dto.PlanningHazardRequestPageResponse listHazardRequests(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "50") int size) {
        return com.csd.occ.planning.diagnosis.api.dto.PlanningHazardRequestPageResponse.from(
                scopes.listHazardRequests(page, size));
    }

    @GetMapping("/{diagnosisId}")
    @Operation(operationId = "getDiagnosis", summary = "Get a diagnosis result when processing has completed")
    @ApiResponse(responseCode = "200", description = "Diagnosis lifecycle and result")
    @ApiResponse(responseCode = "404", description = "Completed diagnosis not found",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public DiagnosisResponse find(@PathVariable UUID diagnosisId) {
        var view = diagnoses.find(diagnosisId);
        return new DiagnosisResponse(view.diagnosisId(), view.status(), view.failureCode(), view.causeCode(),
                view.causeSubtype(), view.likelyCause(), view.confidence(), view.evidenceSupportScore(),
                view.evidenceIds(), view.limitations(), view.completedAt(), view.eventType(), view.eventId(), view.producer(),
                view.recommendationDeliveryStatus(),
                view.recommendationDeliveryFailureCode());
    }
}
