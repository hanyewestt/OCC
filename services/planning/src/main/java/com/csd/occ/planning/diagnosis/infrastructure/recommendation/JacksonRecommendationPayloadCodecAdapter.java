package com.csd.occ.planning.diagnosis.infrastructure.recommendation;

import com.csd.occ.planning.diagnosis.domain.BunchingEventFacts;
import com.csd.occ.planning.diagnosis.domain.RecommendationPayloadCodecPort;
import com.csd.occ.planning.diagnosis.domain.RootCauseDiagnosis;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class JacksonRecommendationPayloadCodecAdapter implements RecommendationPayloadCodecPort {
    private final JsonMapper json;
    public JacksonRecommendationPayloadCodecAdapter(JsonMapper json) { this.json = json; }

    @Override
    public String encode(UUID requestId, BunchingEventFacts event, RootCauseDiagnosis diagnosis, String classification) {
        RecommendationRequest body = new RecommendationRequest(requestId, classification,
                new BunchingEvent(event.bunchingEventId(), event.serviceId(), event.directionId(),
                        event.monitoredStopCode(), event.status(), event.expectedHeadwaySeconds(),
                        event.observedHeadwaySeconds(), event.firstConfirmedAt(), event.evidenceIds()),
                new Diagnosis(diagnosis.id(), diagnosis.likelyCause(), BigDecimal.valueOf(diagnosis.confidence()),
                        diagnosis.supportingEvidenceIds(), diagnosis.limitations()));
        try { return json.writeValueAsString(body); }
        catch (Exception failure) { throw new IllegalStateException("Cannot encode recommendation request", failure); }
    }

    private record RecommendationRequest(UUID requestId, String classification, BunchingEvent bunchingEvent,
                                         Diagnosis diagnosis) { }
    private record BunchingEvent(UUID bunchingEventId, String serviceId, int directionId,
                                 String monitoredStopCode, String status, int expectedHeadwaySeconds,
                                 List<Integer> observedHeadwaySeconds, java.time.Instant firstConfirmedAt,
                                 List<String> evidenceIds) { }
    private record Diagnosis(UUID diagnosisId, String likelyCause, BigDecimal confidence,
                             List<String> evidenceIds, List<String> limitations) { }
}
