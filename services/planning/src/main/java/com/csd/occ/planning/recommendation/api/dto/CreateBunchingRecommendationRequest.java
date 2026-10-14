package com.csd.occ.planning.recommendation.api.dto;

import com.csd.occ.planning.recommendation.domain.SourceClassification;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

@Schema(description = "Request from root-cause diagnosis to create one bus-bunching recommendation")
public record CreateBunchingRecommendationRequest(
        @NotNull UUID requestId,
        @NotNull SourceClassification classification,
        @NotNull @Valid BunchingEventRequest bunchingEvent,
        @NotNull @Valid BunchingRootCauseDiagnosisRequest diagnosis) {
}
