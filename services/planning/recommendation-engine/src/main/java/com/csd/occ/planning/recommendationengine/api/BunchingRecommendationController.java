package com.csd.occ.planning.recommendationengine.api;

import com.csd.occ.planning.recommendationengine.api.dto.BunchingRecommendationResponse;
import com.csd.occ.planning.recommendationengine.api.dto.CreateBunchingRecommendationRequest;
import com.csd.occ.planning.recommendationengine.api.error.NoActionableRecommendationException;
import com.csd.occ.planning.recommendationengine.domain.DurableBunchingRecommendationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/internal/v1/bunching-recommendations")
public class BunchingRecommendationController {

    private final DurableBunchingRecommendationService recommendations;
    private final RecommendationRequestFingerprint fingerprints;
      private final com.csd.occ.planning.recommendationengine.domain.RecommendationRecordPort records;

    public BunchingRecommendationController(
            DurableBunchingRecommendationService recommendations,
            RecommendationRequestFingerprint fingerprints,
            com.csd.occ.planning.recommendationengine.domain.RecommendationRecordPort records) {
        this.recommendations = recommendations;
        this.fingerprints = fingerprints;
        this.records = records;
    }

    @Operation(
            operationId = "createBunchingRecommendation",
            summary = "Create one validated bus-bunching mitigation recommendation",
            description = "Consumes a confirmed simulated bunching event and root-cause diagnosis. "
                    + "Observation-only evidence and inputs without validated fixture context do not produce an action.",
            tags = "Bunching recommendations")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = CreateBunchingRecommendationRequest.class),
                    examples = @ExampleObject(
                            name = "confirmedBunching",
                            value = """
                                    {
                                      "requestId": "6e2f6b21-9c43-4c17-93c5-10b2d820b9a7",
                                      "classification": "SIMULATION",
                                      "bunchingEvent": {
                                        "bunchingEventId": "c5934f15-4a65-4e94-87ea-a5a64a7dd4c1",
                                        "serviceId": "190",
                                        "directionId": 1,
                                        "monitoredStopCode": "44251",
                                        "status": "CONFIRMED",
                                        "expectedHeadwaySeconds": 600,
                                        "observedHeadwaySeconds": [276, 221],
                                        "firstConfirmedAt": "2026-09-27T23:45:02Z",
                                        "evidenceIds": ["route190-arrival-20260928T074402+0800", "route190-arrival-20260928T074502+0800"]
                                      },
                                      "diagnosis": {
                                        "diagnosisId": "f2b8d1b5-c24c-48bb-8a66-bff7be1e9e73",
                                        "likelyCause": "A vehicle breakdown delayed leader SBS8027Y, allowing follower SBS5898U to close the gap.",
                                        "confidence": 0.82,
                                        "evidenceIds": ["route190-breakdown-delay-sbs8027y"],
                                        "limitations": ["The diagnosis is supplied by the shared deterministic simulation fixture."]
                                      }
                                    }
                                    """)))
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "One validated recommendation",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = BunchingRecommendationResponse.class))),
            @ApiResponse(
                    responseCode = "400",
                    description = "Malformed request, invalid field, or missing idempotency key",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(
                    responseCode = "422",
                    description = "The evidence cannot produce a validated actionable recommendation",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(
                    responseCode = "409",
                    description = "The idempotency key was already used with different request content",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public BunchingRecommendationResponse create(
            @Parameter(
                    description = "UUID identifying this recommendation attempt. Reusing the key with the same "
                            + "request returns the stored response; reuse with different content returns 409.",
                    required = true,
                    example = "f07c921a-bd3b-4a97-8b17-a384ef1269a7")
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody CreateBunchingRecommendationRequest request) {
        var recommendation = recommendations.recommend(
                        idempotencyKey,
                        fingerprints.fingerprint(request),
                        request.requestId(),
                        request.classification(),
                        request.bunchingEvent().toDomain(),
                        request.diagnosis().toDomain())
                .orElseThrow(() -> new NoActionableRecommendationException(
                        "No validated mitigation is available for this event and operational context."));

        return BunchingRecommendationResponse.from(
                recommendation.requestId(),
                recommendation.classification(),
                recommendation.recommendation());
    }

    @GetMapping(value = "/by-bunching-event/{bunchingEventId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getBunchingRecommendation", summary = "Get the latest recommendation for a simulated bunching event")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Recommendation returned"),
            @ApiResponse(responseCode = "404", description = "No recommendation is available for the event")
    })
    public ResponseEntity<BunchingRecommendationResponse> findByBunchingEventId(@PathVariable UUID bunchingEventId) {
        return records.findByBunchingEventId(bunchingEventId)
                .map(record -> BunchingRecommendationResponse.from(
                        record.requestId(), record.classification(), record.recommendation()))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
