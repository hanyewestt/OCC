package com.csd.occ.planning.advisory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PublicNoticeTest {
    private static final Instant NOW = Instant.parse("2026-10-25T02:00:00Z");

    @Test
    void publicationAndWithdrawalKeepTheirFirstTimestampsAcrossRetries() {
        var notice = notice(List.of("190"), List.of("School"));
        assertThat(notice.status()).isEqualTo("DRAFT");
        assertThat(notice.publishedAt()).isNull();
        notice.publish("controller", NOW.plusSeconds(1));
        notice.publish("other-controller", NOW.plusSeconds(2));
        assertThat(notice.status()).isEqualTo("PUBLISHED");
        assertThat(notice.publishedAt()).isEqualTo(NOW.plusSeconds(1));
        notice.withdraw(NOW.plusSeconds(3));
        notice.withdraw(NOW.plusSeconds(4));
        assertThat(notice.status()).isEqualTo("WITHDRAWN");
        assertThat(notice.withdrawnAt()).isEqualTo(NOW.plusSeconds(3));
        assertThatThrownBy(() -> notice.publish("controller", NOW.plusSeconds(5)))
                .isInstanceOf(AdvisoryConflictException.class);
    }

    @Test
    void draftCannotBePublishedAtOrAfterItsExactExpiry() {
        var atBoundary = notice(List.of("190"), List.of("School"));
        assertThatThrownBy(() -> atBoundary.publish("controller", NOW.plusSeconds(60)))
                .isInstanceOf(AdvisoryConflictException.class);
        assertThat(atBoundary.status()).isEqualTo("DRAFT");
        assertThat(atBoundary.publishedAt()).isNull();
        var afterBoundary = notice(List.of("190"), List.of("School"));
        assertThatThrownBy(() -> afterBoundary.publish("controller", NOW.plusSeconds(61)))
                .isInstanceOf(AdvisoryConflictException.class);
    }

    @Test
    void publicationDoesNotExposeMutableNoticeContent() {
        var services = new ArrayList<>(List.of("190"));
        var locations = new ArrayList<>(List.of("School"));
        var notice = notice(services, locations);
        notice.publish("controller", NOW.plusSeconds(1));
        services.add("972");
        locations.clear();
        assertThat(notice.services()).containsExactly("190");
        assertThat(notice.locations()).containsExactly("School");
        assertThatThrownBy(() -> notice.services().add("972")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> notice.locations().clear()).isInstanceOf(UnsupportedOperationException.class);
        notice.withdraw(NOW.plusSeconds(2));
        assertThat(notice.title()).isEqualTo("Reported disruption");
        assertThat(notice.message()).isEqualTo("Service 190 may be delayed near School.");
        assertThat(notice.effectiveFrom()).isEqualTo(NOW);
        assertThat(notice.effectiveUntil()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void replacingANoticeWithdrawsItWithoutEditingItsContent() {
        var notice = notice(List.of("190"), List.of("School"));
        notice.publish("controller", NOW.plusSeconds(1));
        UUID replacement = UUID.randomUUID();
        notice.replace(replacement, NOW.plusSeconds(2));
        notice.replace(replacement, NOW.plusSeconds(3));
        assertThat(notice.status()).isEqualTo("WITHDRAWN");
        assertThat(notice.withdrawnAt()).isEqualTo(NOW.plusSeconds(2));
        assertThat(notice.replacementId()).isEqualTo(replacement);
        assertThat(notice.message()).isEqualTo("Service 190 may be delayed near School.");
    }

    private PublicNotice notice(List<String> services, List<String> locations) {
        var facts = new HazardEventFacts.Fact("TRAFFIC_INCIDENT", "incident-1", "OBSERVED", NOW,
                null, null, "Reported disruption", "Reported conditions", null, null, List.of(),
                null, null, null, null, List.of());
        var hazard = new AdvisoryContextPort.Hazard(UUID.randomUUID(), "TRAFFIC_INCIDENT", "ACTIVE", null,
                "OBSERVATION_ONLY", null, UUID.randomUUID(), NOW, facts, List.of(), List.of());
        return new PublicNotice(UUID.randomUUID(), hazard, "Reported disruption", "Service 190 may be delayed near School.",
                services, locations, NOW, NOW.plusSeconds(60), "controller", NOW);
    }
}
