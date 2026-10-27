package com.csd.occ.planning.recommendation.api.dto;

import com.csd.occ.planning.diagnosis.domain.DiagnosisSummaryPolicy;
import com.csd.occ.planning.diagnosis.api.dto.DiagnosisEvidenceDetailResponse;
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
        List<String> limitations,
        @io.swagger.v3.oas.annotations.media.ArraySchema(maxItems = 50,
                arraySchema = @io.swagger.v3.oas.annotations.media.Schema(
                        description = "Source facts supporting the associated diagnosis"))
        List<DiagnosisEvidenceDetailResponse> evidenceDetails) {

    public static RecommendationExplanationResponse from(RecommendationExplanation explanation) {
        return new RecommendationExplanationResponse(
                explanation.rationale(),
                explanation.likelyCause(),
                explanation.diagnosisConfidence(),
                explanation.tradeOffs(),
                explanation.limitations(),
                List.of());
    }

    public static RecommendationExplanationResponse from(RecommendationExplanation explanation,
            DiagnosisSummaryPolicy.Summary diagnosisSummary) {
        return new RecommendationExplanationResponse(explanation.rationale(),
                diagnosisSummary == null ? "Diagnosis details are unavailable for this retained result."
                        : diagnosisSummary.likelyCause(),
                explanation.diagnosisConfidence(), explanation.tradeOffs(), explanation.limitations(),
                diagnosisSummary == null ? List.of() : diagnosisSummary.evidenceDetails().stream()
                        .map(DiagnosisEvidenceDetailResponse::from).toList());
    }
}
