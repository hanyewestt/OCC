package com.csd.occ.planning.diagnosis.infrastructure.jackson;

import com.csd.occ.planning.diagnosis.domain.EvidenceSnapshot;
import com.csd.occ.planning.diagnosis.domain.SnapshotCodecPort;
import com.csd.occ.planning.diagnosis.domain.ConfidenceComponents;
import com.csd.occ.planning.diagnosis.domain.ProducerEventSubmission;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class JacksonSnapshotCodecAdapter implements SnapshotCodecPort {
    private final JsonMapper json;
    public JacksonSnapshotCodecAdapter(JsonMapper json) { this.json = json; }
    @Override public String encode(EvidenceSnapshot snapshot) {
        try { return json.writeValueAsString(snapshot); }
        catch (Exception failure) { throw new IllegalStateException("Cannot encode evidence snapshot", failure); }
    }
    @Override public String encodeStringList(List<String> values) {
        try { return json.writeValueAsString(values); }
        catch (Exception failure) { throw new IllegalStateException("Cannot encode JSON list", failure); }
    }
    @Override public EvidenceSnapshot decode(String value) { return json.readValue(value, EvidenceSnapshot.class); }
    @Override public String encodeConfidenceComponents(ConfidenceComponents components) {
        try { return json.writeValueAsString(components); }
        catch (Exception failure) { throw new IllegalStateException("Cannot encode confidence components", failure); }
    }
    @Override public String encodeProducerEvent(ProducerEventSubmission submission) {
        try { return json.writeValueAsString(submission); }
        catch (Exception failure) { throw new IllegalStateException("Cannot encode producer event snapshot", failure); }
    }
    @Override public ProducerEventSubmission decodeProducerEvent(String value) {
        try { return json.readValue(value, ProducerEventSubmission.class); }
        catch (Exception failure) { throw new IllegalStateException("Persisted producer event snapshot is invalid", failure); }
    }
    @Override public List<String> decodeStringList(String value) {
        try { return List.of(json.readValue(value, String[].class)); }
        catch (Exception failure) { throw new IllegalStateException("Persisted diagnosis JSON is invalid", failure); }
    }
}
