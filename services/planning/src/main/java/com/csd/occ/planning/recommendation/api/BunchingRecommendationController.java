package com.csd.occ.planning.recommendation.api;

import com.csd.occ.planning.recommendation.api.dto.BunchingRecommendationResponse;
import com.csd.occ.planning.recommendation.api.dto.PendingRecommendationPageResponse;
import com.csd.occ.planning.recommendation.api.dto.PendingRecommendationResponse;
import com.csd.occ.planning.recommendation.domain.RecommendationRecordPort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/v1/planning/recommendations", "/internal/v1/planning/recommendations"})
@Tag(name = "Planning recommendations")
public class BunchingRecommendationController {
    private final RecommendationRecordPort records;
    private final com.csd.occ.planning.diagnosis.domain.PlanningScopePort scopes;
    private final com.csd.occ.planning.diagnosis.domain.DiagnosisQueryService diagnoses;

    public BunchingRecommendationController(RecommendationRecordPort records,
            com.csd.occ.planning.diagnosis.domain.PlanningScopePort scopes,
            com.csd.occ.planning.diagnosis.domain.DiagnosisQueryService diagnoses) {
        this.records = records; this.scopes = scopes; this.diagnoses = diagnoses;
    }

    @GetMapping("/by-disruption-event/{bunchingEventId}")
    @Operation(operationId = "getBunchingRecommendation",
            summary = "Get the recommendation for a disruption event")
    @ApiResponse(responseCode = "200", description = "Recommendation returned")
    @ApiResponse(responseCode = "404", description = "No recommendation is available for the event")
    public ResponseEntity<BunchingRecommendationResponse> findByDisruptionEventId(
            @PathVariable UUID bunchingEventId) {
        var record = records.findByBunchingEventId(bunchingEventId).orElse(null);
        if (record == null) return ResponseEntity.notFound().build();
        var summary = diagnoses.summariesByDiagnosisIds(List.of(record.diagnosis().diagnosisId()))
                .get(record.diagnosis().diagnosisId());
        return ResponseEntity.ok(BunchingRecommendationResponse.from(record.requestId(), record.classification(),
                record.recommendation(), summary));
    }

    @GetMapping("/pending")
    @Operation(operationId = "listPendingBunchingRecommendations",
            summary = "List recommendations for the active simulation awaiting a controller decision")
    @ApiResponse(responseCode = "200", description = "Pending recommendations for the selected run; empty in Live mode")
    @ApiResponse(responseCode = "503", description = "Shared data selection is unavailable")
    public PendingRecommendationPageResponse pending(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page and size are outside the supported range");
        }
        var scope = scopes.current();
        if (scope.demoRunId() == null) return new PendingRecommendationPageResponse(List.of(), page, size, 0, 0, scope.mode(), scope.demoRunId());
        var pendingRecords = records.findPendingForRun(scope.demoRunId(), page * size, size);
        var summaries = pendingRecords.isEmpty() ? java.util.Map.<UUID,
                com.csd.occ.planning.diagnosis.domain.DiagnosisSummaryPolicy.Summary>of()
                : diagnoses.summariesByDiagnosisIds(pendingRecords.stream().map(record -> record.diagnosis().diagnosisId()).toList());
        List<PendingRecommendationResponse> items = pendingRecords.stream()
                .map(record -> PendingRecommendationResponse.from(record,
                        summaries.get(record.diagnosis().diagnosisId())))
                .toList();
        long totalItems = records.countPendingForRun(scope.demoRunId());
        int totalPages = totalItems == 0 ? 0 : (int) ((totalItems + size - 1) / size);
        return new PendingRecommendationPageResponse(items, page, size, totalItems, totalPages, scope.mode(), scope.demoRunId());
    }
}
