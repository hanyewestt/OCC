package com.csd.occ.disruption.bunching.infrastructure.jackson;

import com.csd.occ.disruption.bunching.domain.BunchingEvaluation;
import com.csd.occ.disruption.bunching.domain.BunchingEvidencePort;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class JacksonBunchingEvidenceAdapter implements BunchingEvidencePort {
    private final JsonMapper mapper;

    public JacksonBunchingEvidenceAdapter(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String encode(BunchingEvaluation evaluation) {
        return mapper.writeValueAsString(evaluation);
    }

    @Override
    public String encodeAlert(BunchingEvaluation evaluation, BunchingEvaluation.GroupEvaluation group) {
        var key = group.group();
        var relevantArrivals = evaluation.snapshot().arrivals().stream()
                .filter(arrival -> arrival.serviceId().equals(key.serviceId())
                        && arrival.stopCode().equals(key.stopCode())
                        && arrival.directionId().equals(key.directionId()))
                .toList();
        var relevantSnapshot = new com.csd.occ.disruption.bunching.domain.ArrivalSnapshot(
                evaluation.snapshot().rawObservationId(), evaluation.snapshot().pollRunId(),
                evaluation.snapshot().referenceVersionId(), evaluation.snapshot().fetchedAt(), relevantArrivals,
                evaluation.snapshot().classification(), evaluation.snapshot().demoRunId());
        return mapper.writeValueAsString(new BunchingEvaluation(relevantSnapshot, evaluation.evaluatedAt(),
                evaluation.threshold(), evaluation.staleAfter(), java.util.List.of(group)));
    }
}
