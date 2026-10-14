package com.csd.occ.disruption.traffic.infrastructure.jackson;

import com.csd.occ.disruption.traffic.domain.TrafficIncidentEvidencePort;
import com.csd.occ.disruption.traffic.domain.TrafficIncidentPollPort;
import com.csd.occ.disruption.traffic.domain.TrafficIncidentSnapshot;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class JacksonTrafficIncidentEvidenceAdapter implements TrafficIncidentEvidencePort {
    private final JsonMapper mapper;

    public JacksonTrafficIncidentEvidenceAdapter(JsonMapper mapper) { this.mapper = mapper; }

    @Override
    public String encode(TrafficIncidentPollPort.TrafficIncidentPoll poll, TrafficIncidentSnapshot incident,
            String action) {
        return mapper.writeValueAsString(new Snapshot(action, poll.pollRunId(), poll.source(), poll.outcome(),
                poll.complete(), poll.classification(), poll.demoRunId(), poll.completedAt(), incident));
    }

    private record Snapshot(String action, java.util.UUID processingPollRunId, String source, String outcome,
            boolean complete, String classification, java.util.UUID demoRunId, java.time.Instant completedAt,
            TrafficIncidentSnapshot incident) { }
}
