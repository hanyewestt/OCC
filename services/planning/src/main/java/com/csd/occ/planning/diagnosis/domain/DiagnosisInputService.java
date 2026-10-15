package com.csd.occ.planning.diagnosis.domain;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
@Service
public class DiagnosisInputService {
    private final EventFactsPort facts;
    private final ContextEvidencePort context;
    private final SnapshotCodecPort codec;
    public DiagnosisInputService(EventFactsPort facts, ContextEvidencePort context, SnapshotCodecPort codec) {
        this.facts = facts; this.context = context; this.codec = codec;
    }
    public String capture(java.util.UUID eventId) {
        var event = facts.findConfirmedEvent(eventId);
        return capture(event);
    }
    public String capture(BunchingEventFacts event) {
        var evidence = context.findContext(event);
        var ids = Stream.of(event.evidenceIds().stream(), evidence.arrivals().stream().map(ArrivalEvidence::evidenceId),
                evidence.incidents().stream().map(IncidentEvidence::evidenceId)).flatMap(stream -> stream).distinct().toList();
        return codec.encode(new EvidenceSnapshot(event, evidence, ids, event.classification()));
    }
    public boolean matchesProducerFacts(String inputSnapshot, BunchingEventFacts event) {
        return codec.decode(inputSnapshot).event().equals(event);
    }
}
