package com.csd.occ.planning.recommendationengine.api;

import com.csd.occ.planning.recommendationengine.api.dto.CreateBunchingRecommendationRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class RecommendationRequestFingerprint {

    private final JsonMapper json;

    public RecommendationRequestFingerprint(JsonMapper json) {
        this.json = json;
    }

    public String fingerprint(CreateBunchingRecommendationRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] canonicalRequest = json.writeValueAsString(request).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(digest.digest(canonicalRequest));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
