package com.csd.occ.planning.advisory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IssuedInstructionTest {
    private static final Instant ISSUED = Instant.parse("2026-10-25T02:00:00Z");
    private final UUID captain = UUID.randomUUID();
    private final AdvisoryContextPort.Bus bus = new AdvisoryContextPort.Bus("demo-190-b", "trip-190-b", "SBS5898U",
            "190", 1, captain, "ASSIGNED", null);

    @Test
    void fiveMinuteWindowStartsAtAcknowledgementAndEndsAtItsExactBoundary() {
        var instruction = speedInstruction();
        assertThat(instruction.status()).isEqualTo("ISSUED");
        assertThat(instruction.acknowledgedAt()).isNull();
        assertThat(instruction.applicableUntil()).isNull();
        assertThat(instruction.isCurrent(ISSUED.plusSeconds(119))).isTrue();
        Instant acknowledged = ISSUED.plusSeconds(120);
        instruction.acknowledge(captain.toString(), acknowledged);
        assertThat(instruction.status()).isEqualTo("ACKNOWLEDGED");
        assertThat(instruction.acknowledgedAt()).isEqualTo(acknowledged);
        assertThat(instruction.applicableUntil()).isEqualTo(acknowledged.plusSeconds(300));
        assertThat(instruction.isCurrent(ISSUED.plusSeconds(301))).isTrue();
        assertThat(instruction.isCurrent(acknowledged.plusSeconds(299))).isTrue();
        assertThat(instruction.isCurrent(acknowledged.plusSeconds(300))).isFalse();
    }

    @Test
    void repeatedAcknowledgementKeepsOriginalTimeAndDoesNotExtendValidity() {
        var instruction = speedInstruction();
        Instant acknowledged = ISSUED.plusSeconds(120);
        instruction.acknowledge(captain.toString(), acknowledged);
        instruction.acknowledge(captain.toString(), ISSUED.plusSeconds(180));
        instruction.acknowledge(captain.toString(), ISSUED.plusSeconds(900));
        assertThat(instruction.acknowledgedAt()).isEqualTo(acknowledged);
        assertThat(instruction.applicableUntil()).isEqualTo(acknowledged.plusSeconds(300));
        assertThat(instruction.isCurrent(ISSUED.plusSeconds(900))).isFalse();
    }

    @Test
    void acknowledgementUsesPostgresMicrosecondPrecisionForStableRetries() {
        var instruction = speedInstruction();
        Instant unrounded = ISSUED.plusNanos(123_456_789);

        instruction.acknowledge(captain.toString(), unrounded);
        Instant retryTime = instruction.acknowledgedAt();
        instruction.acknowledge(captain.toString(), ISSUED.plusSeconds(1));

        assertThat(retryTime).isEqualTo(unrounded.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThat(instruction.acknowledgedAt()).isEqualTo(retryTime);
        assertThat(instruction.applicableUntil()).isEqualTo(retryTime.plusSeconds(300));
    }

    @Test
    void unacknowledgedInstructionExpiresAtAcknowledgementDeadline() {
        var instruction = speedInstruction();
        assertThat(instruction.acknowledgementDeadline()).isEqualTo(ISSUED.plusSeconds(300));
        assertThat(instruction.isCurrent(ISSUED.plusSeconds(299))).isTrue();
        assertThat(instruction.isCurrent(ISSUED.plusSeconds(300))).isFalse();
        assertThatThrownBy(() -> instruction.acknowledge(captain.toString(), ISSUED.plusSeconds(300)))
                .isInstanceOf(AdvisoryConflictException.class);
        assertThat(instruction.acknowledgedAt()).isNull();
        assertThat(instruction.applicableUntil()).isNull();
    }

    @Test
    void cancelledAndExpiredInstructionsRejectTheirFirstAcknowledgement() {
        var cancelled = speedInstruction();
        cancelled.cancel("CONTROLLER_CANCELLED");
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.inactiveReason()).isEqualTo("CONTROLLER_CANCELLED");
        assertThat(cancelled.isCurrent(ISSUED.plusSeconds(1))).isFalse();
        assertThatThrownBy(() -> cancelled.acknowledge(captain.toString(), ISSUED.plusSeconds(1)))
                .isInstanceOf(AdvisoryConflictException.class);
        var expired = speedInstruction();
        expired.expire("STOP_RANGE_PASSED");
        assertThat(expired.status()).isEqualTo("EXPIRED");
        assertThat(expired.isCurrent(ISSUED.plusSeconds(1))).isFalse();
        assertThatThrownBy(() -> expired.acknowledge(captain.toString(), ISSUED.plusSeconds(1)))
                .isInstanceOf(AdvisoryConflictException.class);
    }

    @Test
    void cancellationAfterAcknowledgementKeepsIdempotentReceiptWithoutBecomingCurrent() {
        var instruction = speedInstruction();
        Instant acknowledged = ISSUED.plusSeconds(10);
        instruction.acknowledge(captain.toString(), acknowledged);
        instruction.cancel("SOURCE_WITHDRAWN");
        instruction.acknowledge(captain.toString(), ISSUED.plusSeconds(20));
        assertThat(instruction.status()).isEqualTo("CANCELLED");
        assertThat(instruction.acknowledgedAt()).isEqualTo(acknowledged);
        assertThat(instruction.isCurrent(ISSUED.plusSeconds(20))).isFalse();
    }

    private IssuedInstruction speedInstruction() {
        var candidate = new AdvisoryCandidate(InstructionType.REDUCE_SPEED,
                new InstructionParameters(null, null, null, null, 5, 300), "Synthetic speed context");
        return new IssuedInstruction(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), bus, candidate,
                "Reduce speed by 5 km/h for 5 min.", "SIMULATION", "controller", ISSUED, Duration.ofMinutes(5));
    }
}
