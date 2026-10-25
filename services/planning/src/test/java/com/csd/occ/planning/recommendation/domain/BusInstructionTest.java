package com.csd.occ.planning.recommendation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BusInstructionTest {
    private static final Instant SENT_AT = Instant.parse("2026-10-24T04:00:00Z");

    @Test
    void acknowledgementMovesSentInstructionToAcknowledged() {
        BusInstruction sent = BusInstruction.sent(UUID.randomUUID(), UUID.randomUUID(),
                " SBS5898U ", " Hold for 120 seconds. ", SENT_AT);

        BusInstruction acknowledged = sent.acknowledge(
                " captain-subject ", SENT_AT.plusSeconds(5));

        assertThat(acknowledged.status()).isEqualTo(BusInstructionStatus.ACKNOWLEDGED);
        assertThat(acknowledged.busReference()).isEqualTo("SBS5898U");
        assertThat(acknowledged.instruction()).isEqualTo("Hold for 120 seconds.");
        assertThat(acknowledged.acknowledgedAt()).contains(SENT_AT.plusSeconds(5));
        assertThat(acknowledged.acknowledgedBy()).contains("captain-subject");
    }

    @Test
    void repeatedAcknowledgementKeepsTheFirstAcknowledgement() {
        BusInstruction acknowledged = BusInstruction.sent(UUID.randomUUID(), UUID.randomUUID(),
                "SBS5898U", "Hold for 120 seconds.", SENT_AT)
                .acknowledge("first-captain", SENT_AT.plusSeconds(5));

        assertThat(acknowledged.acknowledge("second-captain", SENT_AT.plusSeconds(10)))
                .isSameAs(acknowledged);
    }

    @Test
    void acknowledgementCannotPredateDelivery() {
        BusInstruction sent = BusInstruction.sent(UUID.randomUUID(), UUID.randomUUID(),
                "SBS5898U", "Hold for 120 seconds.", SENT_AT);

        assertThatThrownBy(() -> sent.acknowledge("captain-subject", SENT_AT.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("acknowledgedAt");
    }
}
