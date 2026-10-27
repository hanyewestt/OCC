package com.csd.occ.ingestion.infrastructure.datamall;

import com.csd.occ.ingestion.domain.HazardEvidencePort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class JacksonHazardEvidenceAdapter implements HazardEvidencePort {
    private final JsonMapper json;
    public JacksonHazardEvidenceAdapter(JsonMapper json) { this.json = json; }
    public String encodePages(List<String> pages) { return json.writeValueAsString(pages); }
    public String encodeFact(com.csd.occ.ingestion.domain.HazardFeedPort.Fact fact) { return json.writeValueAsString(fact); }
    public String checksum(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
