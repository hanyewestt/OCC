package com.csd.occ.diagnosis.infrastructure.bunching;

import com.csd.occ.diagnosis.domain.BunchingEventFacts;
import com.csd.occ.diagnosis.domain.DiagnosisFailureException;
import com.csd.occ.diagnosis.domain.EvidenceLimitExceededException;
import com.csd.occ.diagnosis.infrastructure.http.PeerResponseLimitExceededException;
import com.csd.occ.diagnosis.domain.EventFactsPort;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class HttpBunchingEventFactsAdapter implements EventFactsPort {
    private final RestClient client;

    public HttpBunchingEventFactsAdapter(@Qualifier("bunchingFactsRestClient") RestClient client) { this.client = client; }

    @Override
    public BunchingEventFacts findConfirmedEvent(UUID eventId) {
        try {
            EventResponse response = client.get().uri("/internal/v1/bunching-alert-events/{eventId}", eventId)
                    .retrieve().body(EventResponse.class);
            if (response == null) throw new DiagnosisFailureException("EMPTY_EVENT_FACTS", true);
            if (!"SIMULATION".equals(response.classification()))
                throw new DiagnosisFailureException("NON_SIMULATION_EVENT", false);
            if (!"OPENED".equals(response.action()) || response.expectedHeadwaySeconds() == null
                    || response.observedHeadwaySeconds() == null || response.observedHeadwaySeconds().size() < 2
                    || response.observedHeadwaySeconds().size() > 10)
                throw new DiagnosisFailureException("INCOMPLETE_EVENT_FACTS", false);
            return new BunchingEventFacts(response.id(), response.bunchingEventId(), response.classification(),
                    response.demoRunId(), response.serviceId(), response.directionId(), response.monitoredStopCode(),
                    response.leadingObservationId(), response.followingObservationId(), "CONFIRMED",
                    response.expectedHeadwaySeconds(), response.observedHeadwaySeconds(),
                    response.firstConfirmedAt(), response.confirmingObservationTimes(),
                    response.confirmingObservationIds(), response.evidenceIds(),
                    response.detectionRuleVersion());
        } catch (RestClientResponseException failure) {
            int status = failure.getStatusCode().value();
            throw new DiagnosisFailureException(status == 404 ? "EVENT_NOT_FOUND" : "EVENT_FACTS_HTTP_" + status,
                    status == 408 || status == 429 || status >= 500, failure);
        } catch (DiagnosisFailureException failure) {
            throw failure;
        } catch (EvidenceLimitExceededException failure) {
            throw new DiagnosisFailureException("EVIDENCE_LIMIT_EXCEEDED", false, failure);
        } catch (RuntimeException failure) {
            if (hasResponseLimitCause(failure))
                throw new DiagnosisFailureException("EVIDENCE_LIMIT_EXCEEDED", false, failure);
            throw new DiagnosisFailureException("EVENT_FACTS_UNAVAILABLE", true, failure);
        }
    }

    private boolean hasResponseLimitCause(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause())
            if (current instanceof PeerResponseLimitExceededException) return true;
        return false;
    }

    private record EventResponse(UUID id, UUID bunchingEventId, String action, String classification,
                                 UUID demoRunId, String serviceId, int directionId, String monitoredStopCode,
                                 UUID leadingObservationId, UUID followingObservationId,
                                 Integer expectedHeadwaySeconds, List<Integer> observedHeadwaySeconds,
                                 Instant firstConfirmedAt, List<Instant> confirmingObservationTimes,
                                 List<UUID> confirmingObservationIds, List<String> evidenceIds,
                                 String detectionRuleVersion) { }
}
