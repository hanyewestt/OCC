package com.csd.occ.planning.diagnosis.infrastructure.jackson;

import com.csd.occ.planning.diagnosis.domain.EventSubmissionFingerprintPort;
import com.csd.occ.planning.diagnosis.domain.ProducerEventSubmission;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class JacksonEventSubmissionFingerprintAdapter implements EventSubmissionFingerprintPort {
    private final JsonMapper json;

    public JacksonEventSubmissionFingerprintAdapter(JsonMapper json) {
        this.json = json;
    }

    @Override
    public String fingerprint(ProducerEventSubmission submission) {
        try {
            byte[] canonicalPayload = json.writeValueAsBytes(submission);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalPayload));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
