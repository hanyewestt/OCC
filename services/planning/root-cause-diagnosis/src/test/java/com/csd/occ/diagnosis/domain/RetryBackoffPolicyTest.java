package com.csd.occ.diagnosis.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetryBackoffPolicyTest {
    private final RetryBackoffPolicy policy = new RetryBackoffPolicy();

    @Test
    void doublesDelayAndAppliesBoundedJitter() {
        assertThat(policy.delay(Duration.ofSeconds(2), Duration.ofSeconds(60), 1, -0.20))
                .isEqualTo(Duration.ofMillis(1_600));
        assertThat(policy.delay(Duration.ofSeconds(2), Duration.ofSeconds(60), 3, 0.20))
                .isEqualTo(Duration.ofMillis(9_600));
    }

    @Test
    void capsDelayAfterJitter() {
        assertThat(policy.delay(Duration.ofSeconds(10), Duration.ofSeconds(60), 8, 0.20))
                .isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void rejectsInvalidAttemptAndJitter() {
        assertThatThrownBy(() -> policy.delay(Duration.ofSeconds(1), Duration.ofSeconds(5), 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.delay(Duration.ofSeconds(1), Duration.ofSeconds(5), 1, 0.21))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
