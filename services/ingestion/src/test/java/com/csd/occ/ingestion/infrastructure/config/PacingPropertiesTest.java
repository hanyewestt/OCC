package com.csd.occ.ingestion.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class PacingPropertiesTest {
    private static final Duration TEN_SECONDS = Duration.ofSeconds(10);
    private static final Duration TARGET = Duration.ofSeconds(180);

    @Test
    void acceptsTheDocumentedDefaults() {
        assertThatCode(() -> new PacingProperties(TEN_SECONDS, TARGET, 0.5, 1.5, TARGET, Duration.ofMinutes(30), 200))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsSettingsThatWouldMakePacingMeaningless() {
        assertThatThrownBy(() -> new PacingProperties(Duration.ZERO, TARGET, 0.5, 1.5, TARGET, TARGET, 1))
                .hasMessageContaining("interval");
        assertThatThrownBy(() -> new PacingProperties(TEN_SECONDS, null, 0.5, 1.5, TARGET, TARGET, 1))
                .hasMessageContaining("target-headway");
        assertThatThrownBy(() -> new PacingProperties(TEN_SECONDS, TARGET, 1.0, 1.5, TARGET, TARGET, 1))
                .hasMessageContaining("too-close-ratio");
        assertThatThrownBy(() -> new PacingProperties(TEN_SECONDS, TARGET, 0.5, 1.0, TARGET, TARGET, 1))
                .hasMessageContaining("too-far-ratio");
        assertThatThrownBy(() -> new PacingProperties(TEN_SECONDS, TARGET, 0.5, 1.5, TARGET, TARGET, 0))
                .hasMessageContaining("max-subscribers");
    }
}
