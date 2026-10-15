package com.csd.occ.planning.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GeminiEndpointPropertiesTest {
    @Test
    void blankBaseUrlKeepsTheOfficialGeminiEndpoint() {
        assertFalse(new GeminiEndpointProperties("").isConfigured());
        assertFalse(new GeminiEndpointProperties(null).isConfigured());
    }

    @Test
    void acceptsAnAbsoluteCompatibleHttpEndpoint() {
        assertTrue(new GeminiEndpointProperties("http://model.stub:8080/gemini").isConfigured());
    }

    @Test
    void rejectsInvalidOrCredentialBearingEndpoints() {
        assertThrows(IllegalArgumentException.class, () -> new GeminiEndpointProperties("model.stub"));
        assertThrows(IllegalArgumentException.class, () -> new GeminiEndpointProperties("ftp://model.stub"));
        assertThrows(IllegalArgumentException.class, () -> new GeminiEndpointProperties("https://user:secret@model.stub"));
        assertThrows(IllegalArgumentException.class, () -> new GeminiEndpointProperties("https://model.stub?key=secret"));
        assertThrows(IllegalArgumentException.class, () -> new GeminiEndpointProperties("https://model.stub#fragment"));
    }
}
