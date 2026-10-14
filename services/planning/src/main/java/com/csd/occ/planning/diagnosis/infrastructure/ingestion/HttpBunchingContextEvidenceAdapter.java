package com.csd.occ.planning.diagnosis.infrastructure.ingestion;

import com.csd.occ.planning.diagnosis.domain.ArrivalEvidence;
import com.csd.occ.planning.diagnosis.domain.BunchingEventFacts;
import com.csd.occ.planning.diagnosis.domain.ContextEvidencePort;
import com.csd.occ.planning.diagnosis.domain.DiagnosisFailureException;
import com.csd.occ.planning.diagnosis.domain.EvidenceLimitExceededException;
import com.csd.occ.planning.diagnosis.domain.IncidentEvidence;
import com.csd.occ.planning.diagnosis.domain.RootCauseContext;
import com.csd.occ.planning.diagnosis.infrastructure.http.PeerResponseLimitExceededException;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class HttpBunchingContextEvidenceAdapter implements ContextEvidencePort {
    private final RestClient client;

    public HttpBunchingContextEvidenceAdapter(@Qualifier("ingestionContextRestClient") RestClient client) {
        this.client = client;
    }

    @Override
    public RootCauseContext findContext(BunchingEventFacts event) {
        try {
            ContextResponse result = client.get().uri(uri -> uri
                    .path("/internal/v1/ingestion/demo-runs/{demoRunId}/planning-context")
                    .queryParam("at", event.firstConfirmedAt())
                    .queryParam("serviceId", event.serviceId())
                    .queryParam("stopCode", event.monitoredStopCode())
                    .queryParam("directionId", event.directionId())
                    .queryParam("arrivalObservationIds", event.confirmingObservationIds().toArray())
                    .build(event.demoRunId()))
                    .retrieve().body(ContextResponse.class);
            if (result == null || !"SIMULATION".equals(result.classification())
                    || !event.demoRunId().equals(result.demoRunId()))
                throw new DiagnosisFailureException("EVIDENCE_PROVENANCE_MISMATCH", false);
            List<UUID> requestedIds = event.confirmingObservationIds().stream().distinct().sorted().toList();
            List<UUID> returnedIds = result.arrivals().stream().map(ArrivalEvidence::observationId)
                    .distinct().sorted().toList();
            if (!requestedIds.equals(returnedIds))
                throw new DiagnosisFailureException("EVIDENCE_OBSERVATION_SET_MISMATCH", false);
            return new RootCauseContext(result.classification(), result.demoRunId(), result.referenceVersionId(),
                    result.arrivals(), result.incidents(), result.missingFields());
        } catch (RestClientResponseException failure) {
            int status = failure.getStatusCode().value();
            throw new DiagnosisFailureException("CONTEXT_HTTP_" + status,
                    status == 408 || status == 429 || status >= 500, failure);
        } catch (DiagnosisFailureException failure) {
            throw failure;
        } catch (EvidenceLimitExceededException failure) {
            throw new DiagnosisFailureException("EVIDENCE_LIMIT_EXCEEDED", false, failure);
        } catch (RuntimeException failure) {
            if (hasResponseLimitCause(failure))
                throw new DiagnosisFailureException("EVIDENCE_LIMIT_EXCEEDED", false, failure);
            throw new DiagnosisFailureException("CONTEXT_UNAVAILABLE", true, failure);
        }
    }

    private boolean hasResponseLimitCause(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause())
            if (current instanceof PeerResponseLimitExceededException) return true;
        return false;
    }

    private record ContextResponse(String classification, UUID demoRunId, UUID referenceVersionId,
                                   List<ArrivalEvidence> arrivals, List<IncidentEvidence> incidents,
                                   List<String> missingFields) { }
}
