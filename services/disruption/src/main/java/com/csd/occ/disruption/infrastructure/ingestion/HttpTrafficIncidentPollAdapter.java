package com.csd.occ.disruption.infrastructure.ingestion;

import com.csd.occ.disruption.traffic.domain.TrafficIncidentPollPort;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpTrafficIncidentPollAdapter implements TrafficIncidentPollPort {
    private final RestClient ingestion;

    public HttpTrafficIncidentPollAdapter(RestClient ingestionRestClient) { this.ingestion = ingestionRestClient; }

    @Override
    public TrafficIncidentPollPage page(Instant afterCompletedAt, UUID afterPollRunId, int limit) {
        PageResponse response = ingestion.get().uri(uri -> {
            var builder = uri.path("/internal/v1/traffic-incident-polls").queryParam("limit", limit);
            if (afterCompletedAt != null && afterPollRunId != null)
                builder.queryParam("afterCompletedAt", afterCompletedAt).queryParam("afterPollRunId", afterPollRunId);
            return builder.build();
        }).retrieve().body(PageResponse.class);
        if (response == null) throw new IllegalStateException("Ingestion traffic poll feed returned no body");
        return new TrafficIncidentPollPage(response.polls().stream().map(poll -> new TrafficIncidentPoll(
                poll.pollRunId(), poll.source(), poll.outcome(), poll.complete(), poll.classification(),
                poll.demoRunId(), poll.startedAt(), poll.completedAt(), poll.incidents().stream().map(incident ->
                        new TrafficIncidentFact(incident.id(), incident.rawObservationId(), incident.sourceIncidentKey(),
                                incident.incidentType(), incident.message(), incident.latitude(), incident.longitude(),
                                incident.observedAt(), incident.fetchedAt(), incident.classification(), incident.demoRunId())).toList()))
                .toList(), response.hasMore());
    }

    private record PageResponse(List<PollResponse> polls, boolean hasMore) { }
    private record PollResponse(UUID pollRunId, String source, String outcome, boolean complete, String classification,
            UUID demoRunId, Instant startedAt, Instant completedAt, List<IncidentResponse> incidents) { }
    private record IncidentResponse(UUID id, UUID rawObservationId, String sourceIncidentKey, String incidentType,
            String message, BigDecimal latitude, BigDecimal longitude, Instant observedAt, Instant fetchedAt,
            String classification, UUID demoRunId) { }
}
