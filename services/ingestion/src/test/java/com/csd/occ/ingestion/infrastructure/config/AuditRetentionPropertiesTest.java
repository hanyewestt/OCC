package com.csd.occ.ingestion.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AuditRetentionPropertiesTest {
    @Test void acceptsIndependentPositiveAuditRetention() {
        assertThat(new AuditRetentionProperties(Duration.ofDays(90)).retention()).isEqualTo(Duration.ofDays(90));
    }

    @Test void rejectsMissingOrNonpositiveAuditRetention() {
        for (Duration value : new Duration[] {null, Duration.ZERO, Duration.ofSeconds(-1)})
            assertThatIllegalArgumentException().isThrownBy(() -> new AuditRetentionProperties(value));
    }
}
