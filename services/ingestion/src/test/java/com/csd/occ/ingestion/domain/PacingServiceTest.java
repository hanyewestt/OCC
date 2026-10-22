package com.csd.occ.ingestion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PacingServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-21T05:00:00Z");
    private final BusArrivalObservationRepository arrivals = mock(BusArrivalObservationRepository.class);
    private final PacingService service = new PacingService(arrivals, settings(2),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void latestIsUnavailableUntilTheFirstCalculation() {
        assertThatThrownBy(service::latest).isInstanceOf(PacingUnavailableException.class);
    }

    @Test
    void refreshCalculatesFromCurrentPredictionsAndPushesToSubscribers() {
        when(arrivals.findPacingArrivals()).thenReturn(List.of(
                view("DEMO-BUS-190-01", NOW.plusSeconds(120)), view("DEMO-BUS-190-02", NOW.plusSeconds(165))));
        List<PacingSnapshot> received = new ArrayList<>();
        service.subscribe(received::add);

        PacingSnapshot snapshot = service.refresh();

        assertThat(received).containsExactly(snapshot);
        assertThat(service.latest()).isEqualTo(snapshot);
        assertThat(snapshot.calculatedAt()).isEqualTo(NOW);
        assertThat(snapshot.metrics()).extracting(PacingMetric::vehicleReference)
                .containsExactly("DEMO-BUS-190-01", "DEMO-BUS-190-02");
        assertThat(snapshot.metrics().get(1).ahead().orElseThrow().state()).isEqualTo(PacingState.TOO_CLOSE);
    }

    @Test
    void newSubscriberReceivesTheCurrentSnapshotImmediately() {
        when(arrivals.findPacingArrivals()).thenReturn(List.of());
        PacingSnapshot snapshot = service.refresh();
        List<PacingSnapshot> received = new ArrayList<>();

        service.subscribe(received::add);

        assertThat(received).containsExactly(snapshot);
    }

    @Test
    void failedOrCancelledSubscribersStopReceivingWithoutAffectingOthers() {
        when(arrivals.findPacingArrivals()).thenReturn(List.of());
        List<PacingSnapshot> healthy = new ArrayList<>();
        service.subscribe(snapshot -> { throw new IllegalStateException("client disconnected"); });
        service.subscribe(healthy::add);

        service.refresh();
        service.refresh();

        assertThat(healthy).hasSize(2);
        assertThat(service.subscriberCount()).isEqualTo(1);
    }

    @Test
    void subscriberLimitIsEnforcedAndCancellingFreesASlot() {
        var first = service.subscribe(snapshot -> {});
        service.subscribe(snapshot -> {});

        assertThatThrownBy(() -> service.subscribe(snapshot -> {}))
                .isInstanceOf(PacingSubscriberLimitException.class);
        first.cancel();
        service.subscribe(snapshot -> {});
        assertThat(service.subscriberCount()).isEqualTo(2);
    }

    private static PacingArrivalView view(String registration, Instant eta) {
        return new PacingArrivalView() {
            public String getClassification() { return "SIMULATION"; }
            public String getStopCode() { return "44251"; }
            public String getServiceId() { return "190"; }
            public Integer getDirectionId() { return 1; }
            public Instant getEstimatedArrivalAt() { return eta; }
            public Instant getFetchedAt() { return NOW; }
            public String getVehicleReference() { return registration; }
        };
    }

    private static PacingConfigurationPort settings(int maxSubscribers) {
        return new PacingConfigurationPort() {
            public Duration targetHeadway() { return Duration.ofSeconds(180); }
            public double tooCloseRatio() { return 0.5; }
            public double tooFarRatio() { return 1.5; }
            public Duration staleAfter() { return Duration.ofSeconds(180); }
            public Duration streamTimeout() { return Duration.ofMinutes(30); }
            public int maxSubscribers() { return maxSubscribers; }
        };
    }
}
