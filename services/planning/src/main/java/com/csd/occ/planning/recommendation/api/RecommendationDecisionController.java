package com.csd.occ.planning.recommendation.api;

import com.csd.occ.planning.recommendation.api.dto.RecommendationDecisionRequest;
import com.csd.occ.planning.recommendation.api.dto.RecommendationDecisionResponse;
import com.csd.occ.planning.recommendation.domain.RecommendationDecisionService;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/v1/planning/recommendations")
@Tag(name = "Planning recommendations")
public class RecommendationDecisionController {
    private static final Logger log = LoggerFactory.getLogger(RecommendationDecisionController.class);
    private final RecommendationDecisionService decisions;
    private final MeterRegistry metrics;

    public RecommendationDecisionController(RecommendationDecisionService decisions, MeterRegistry metrics) {
        this.decisions = decisions;
        this.metrics = metrics;
    }

    @PostMapping("/{recommendationId}/decision")
    @Operation(operationId = "decideBunchingRecommendation",
            summary = "Approve or reject a recommendation with one click",
            description = "The first decision is stored with the authenticated controller subject. Repeating the same decision is safe; attempting the opposite decision returns 409. Approval records controller intent only and does not dispatch a bus instruction.")
    @ApiResponse(responseCode = "200", description = "Decision stored or the same decision replayed")
    @ApiResponse(responseCode = "400", description = "Decision request is invalid")
    @ApiResponse(responseCode = "404", description = "Recommendation not found")
    @ApiResponse(responseCode = "409", description = "A different or ineligible decision was supplied")
    public RecommendationDecisionResponse decide(
            @PathVariable UUID recommendationId,
            @Valid @RequestBody RecommendationDecisionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        try {
            var decision = decisions.decide(recommendationId, request.action(), jwt.getSubject());
            metrics.counter("occ.planning.recommendation.decision", "action", request.action().name(),
                    "result", "accepted_or_replayed").increment();
            log.info("Recommendation decision recorded: recommendationId={} action={}",
                    recommendationId, request.action());
            return RecommendationDecisionResponse.from(decision);
        } catch (RecommendationDecisionService.RecommendationDecisionConflictException exception) {
            metrics.counter("occ.planning.recommendation.decision", "action", request.action().name(),
                    "result", "conflict").increment();
            log.warn("Recommendation decision conflict: recommendationId={} action={}",
                    recommendationId, request.action());
            throw exception;
        } catch (RecommendationDecisionService.RecommendationNotActionableException exception) {
            metrics.counter("occ.planning.recommendation.decision", "action", request.action().name(),
                    "result", "not_actionable").increment();
            throw exception;
        }
    }

    @GetMapping("/{recommendationId}/decision")
    @Operation(operationId = "getBunchingRecommendationDecision",
            summary = "Get a recommendation's saved controller decision")
    @ApiResponse(responseCode = "200", description = "Saved decision returned")
    @ApiResponse(responseCode = "204", description = "Recommendation has not been reviewed")
    @ApiResponse(responseCode = "404", description = "Recommendation not found")
    public ResponseEntity<RecommendationDecisionResponse> decision(@PathVariable UUID recommendationId) {
        return decisions.findDecision(recommendationId)
                .map(RecommendationDecisionResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
