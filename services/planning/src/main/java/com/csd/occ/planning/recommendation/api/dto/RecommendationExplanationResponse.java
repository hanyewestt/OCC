package com.csd.occ.planning.recommendation.api.dto;

import com.csd.occ.planning.recommendation.domain.RecommendationExplanation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

@Schema(description = "Why the mitigation was selected and the trade-offs visible to the controller")
public record RecommendationExplanationResponse(
        String rationale,
        String likelyCause,
        BigDecimal diagnosisConfidence,
        List<String> tradeOffs,
        List<String> limitations) {

    public static RecommendationExplanationResponse from(RecommendationExplanation explanation) {
        return new RecommendationExplanationResponse(
                explanation.rationale(),
                explanation.likelyCause(),
                explanation.diagnosisConfidence(),
                explanation.tradeOffs(),
                explanation.limitations());
    }
}
