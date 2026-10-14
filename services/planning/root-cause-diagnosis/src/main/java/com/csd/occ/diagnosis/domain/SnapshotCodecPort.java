package com.csd.occ.diagnosis.domain;

public interface SnapshotCodecPort {
    String encode(EvidenceSnapshot snapshot);
    EvidenceSnapshot decode(String json);
    String encodeStringList(java.util.List<String> values);
    String encodeConfidenceComponents(ConfidenceComponents components);
    java.util.List<String> decodeStringList(String json);
}
