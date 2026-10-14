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

    public DiagnosisController(DiagnosisQueryService diagnoses) { this.diagnoses = diagnoses; }

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
