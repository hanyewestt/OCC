package com.csd.occ.diagnosis.infrastructure.jackson;

import com.csd.occ.diagnosis.domain.EvidenceSnapshot;
import com.csd.occ.diagnosis.domain.SnapshotCodecPort;
import com.csd.occ.diagnosis.domain.ConfidenceComponents;
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
    @Override public String encodeConfidenceComponents(ConfidenceComponents components) {
        try { return json.writeValueAsString(components); }
        catch (Exception failure) { throw new IllegalStateException("Cannot encode confidence components", failure); }
    }
    @Override public List<String> decodeStringList(String value) {
        try { return List.of(json.readValue(value, String[].class)); }
        catch (Exception failure) { throw new IllegalStateException("Persisted diagnosis JSON is invalid", failure); }
    }
}
