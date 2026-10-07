package com.csd.occ.ingestion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BusArrivalServiceTest {
    private final PollTargetRepository targets = mock(PollTargetRepository.class);
    private final PollRunRepository runs = mock(PollRunRepository.class);
    private final RawObservationRepository observations = mock(RawObservationRepository.class);
    private final BusArrivalObservationRepository normalized = mock(BusArrivalObservationRepository.class);
    private final BusRouteStopRepository routeStops = mock(BusRouteStopRepository.class);
    private final FeedStatusRepository feedStatuses = mock(FeedStatusRepository.class);
    private final BusArrivalPort arrivals = mock(BusArrivalPort.class);
    private final ReferenceTopologyPort topology = mock(ReferenceTopologyPort.class);
    private final PollingConfigurationPort configuration = mock(PollingConfigurationPort.class);
    private final Instant now = Instant.parse("2026-09-24T00:00:00Z");
    private final BusArrivalService service = new BusArrivalService(targets, runs, observations, normalized,
            routeStops, feedStatuses, arrivals, topology, configuration, Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void successfulPollPreservesObservationAndMinuteBucket() {
        when(targets.findAll()).thenReturn(List.of(target(true)));
        when(arrivals.arrivalsAt("44009", "190")).thenReturn(snapshot());
        when(topology.activeVersion()).thenReturn(java.util.Optional.of(
                new ReferenceTopologyPort.ReferenceTopologyVersion(java.util.UUID.randomUUID(), "checksum")));
        when(configuration.staleAfterSeconds()).thenReturn(180);
        service.poll();
        ArgumentCaptor<RawObservation> captured = ArgumentCaptor.forClass(RawObservation.class);
        verify(observations).save(captured.capture());
        RawObservation observation = captured.getValue();
        assertThat(observation.getPayload()).isEqualTo("{}");
        assertThat(observation.getPayloadChecksum()).isEqualTo("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a");
        assertThat(observation.getFetchedAt()).isEqualTo(now);
        assertThat(observation.getClassification()).isEqualTo("OBSERVATION_ONLY");
        ArgumentCaptor<PollRun> capturedRun = ArgumentCaptor.forClass(PollRun.class);
        verify(runs, times(2)).save(capturedRun.capture());
        PollRun run = capturedRun.getValue();
        assertThat(run.getOutcome()).isEqualTo("SUCCESS");
        assertThat(run.getIdempotencyKey()).isEqualTo("DATAMALL-stop:44009-" + now.getEpochSecond() / 60);
        assertThat(run.getCompletedAt()).isEqualTo(now);
        assertThat(observation.getPollRunId()).isEqualTo(run.getId());
    }

    @Test
    void disabledTargetsDoNotCallProviderOrWriteRows() {
        when(targets.findAll()).thenReturn(List.of(target(false)));
        service.poll();
        verifyNoInteractions(arrivals, runs, observations, normalized, feedStatuses);
    }

    @Test
    void legacyStopOnlyTargetsDoNotCallProviderOrWriteRows() {
        PollTarget legacyTarget = target(true);
        legacyTarget.setServiceId(null);
        when(targets.findAll()).thenReturn(List.of(legacyTarget));

        service.poll();

        verifyNoInteractions(arrivals, runs, observations, normalized, feedStatuses);
    }

    @Test
    void duplicateMinuteBucketSkipsProviderAndPersistence() {
        when(targets.findAll()).thenReturn(List.of(target(true)));
        when(topology.activeVersion()).thenReturn(java.util.Optional.of(
                new ReferenceTopologyPort.ReferenceTopologyVersion(UUID.randomUUID(), "checksum")));
        when(runs.findByIdempotencyKey("DATAMALL-stop:44009-" + now.getEpochSecond() / 60))
                .thenReturn(java.util.Optional.of(new PollRun()));

        service.poll();

        verify(runs, never()).save(any());
        verifyNoInteractions(arrivals, observations, normalized, feedStatuses);
    }

    @Test
    void missingTopologySkipsAllProviderAndPersistenceWork() {
        when(targets.findAll()).thenReturn(List.of(target(true)));
        when(topology.activeVersion()).thenReturn(java.util.Optional.empty());
        service.poll();
        verifyNoInteractions(arrivals, runs, observations, normalized, feedStatuses);
    }

    @Test
    void providerFailureCompletesFailedRunWithoutObservation() {
        when(targets.findAll()).thenReturn(List.of(target(true)));
        when(topology.activeVersion()).thenReturn(java.util.Optional.of(
                new ReferenceTopologyPort.ReferenceTopologyVersion(java.util.UUID.randomUUID(), "checksum")));
        when(configuration.staleAfterSeconds()).thenReturn(180);
        when(arrivals.arrivalsAt("44009", "190")).thenThrow(new IllegalStateException("synthetic failure"));
        service.poll();
        verifyNoInteractions(observations, normalized);
        ArgumentCaptor<PollRun> captured = ArgumentCaptor.forClass(PollRun.class);
        verify(runs, times(2)).save(captured.capture());
        assertThat(captured.getValue().getOutcome()).isEqualTo("FAILED");
        assertThat(captured.getValue().getCompletedAt()).isEqualTo(now);
    }

    @Test
    void unsuccessfulHttpResponseCompletesFailedRunAndUpdatesFeedStatus() {
        when(targets.findAll()).thenReturn(List.of(target(true)));
        when(topology.activeVersion()).thenReturn(java.util.Optional.of(
                new ReferenceTopologyPort.ReferenceTopologyVersion(UUID.randomUUID(), "checksum")));
        when(arrivals.arrivalsAt("44009", "190"))
                .thenReturn(new BusArrivalSnapshot(503, "{}", List.of(), List.of()));
        when(configuration.staleAfterSeconds()).thenReturn(180);

        service.poll();

        ArgumentCaptor<PollRun> captured = ArgumentCaptor.forClass(PollRun.class);
        verify(runs, times(2)).save(captured.capture());
        assertThat(captured.getValue().getOutcome()).isEqualTo("FAILED");
        assertThat(captured.getValue().getErrorCode()).isEqualTo("503");
        ArgumentCaptor<FeedStatus> status = ArgumentCaptor.forClass(FeedStatus.class);
        verify(feedStatuses).save(status.capture());
        assertThat(status.getValue().getStatus()).isEqualTo("DEGRADED");
    }

    @Test
    void successfulHttpResponseWithoutPayloadIsFailed() {
        when(targets.findAll()).thenReturn(List.of(target(true)));
        when(topology.activeVersion()).thenReturn(java.util.Optional.of(
                new ReferenceTopologyPort.ReferenceTopologyVersion(UUID.randomUUID(), "checksum")));
        when(arrivals.arrivalsAt("44009", "190"))
                .thenReturn(new BusArrivalSnapshot(200, null, List.of(), List.of()));
        when(configuration.staleAfterSeconds()).thenReturn(180);

        service.poll();

        ArgumentCaptor<PollRun> captured = ArgumentCaptor.forClass(PollRun.class);
        verify(runs, times(2)).save(captured.capture());
        assertThat(captured.getValue().getOutcome()).isEqualTo("FAILED");
        verifyNoInteractions(observations, normalized);
    }

    @Test
    void parseErrorsProducePartialOutcomeAndBoundedFeedMessage() {
        when(targets.findAll()).thenReturn(List.of(target(true)));
        when(topology.activeVersion()).thenReturn(java.util.Optional.of(
                new ReferenceTopologyPort.ReferenceTopologyVersion(UUID.randomUUID(), "checksum")));
        when(arrivals.arrivalsAt("44009", "190"))
                .thenReturn(new BusArrivalSnapshot(200, "{}", List.of(), List.of("unparseable record")));
        when(configuration.monitoredServices()).thenReturn(List.of("190"));
        when(configuration.staleAfterSeconds()).thenReturn(180);

        service.poll();

        ArgumentCaptor<PollRun> run = ArgumentCaptor.forClass(PollRun.class);
        verify(runs, times(2)).save(run.capture());
        assertThat(run.getValue().getOutcome()).isEqualTo("PARTIAL");
        assertThat(run.getValue().getErrorMessage()).isEqualTo("unparseable record");
        ArgumentCaptor<FeedStatus> status = ArgumentCaptor.forClass(FeedStatus.class);
        verify(feedStatuses).save(status.capture());
        assertThat(status.getValue().getStatus()).isEqualTo("HEALTHY");
        assertThat(status.getValue().getMessage()).isEqualTo("Some records could not be parsed");
    }

    @Test
    void successfulPollStoresOnlyConfiguredServices() {
        when(targets.findAll()).thenReturn(List.of(target(true)));
        when(arrivals.arrivalsAt("44009", "190")).thenReturn(new BusArrivalSnapshot(200, "{}", List.of(
                prediction("190"), prediction("972"), prediction("999")), List.of()));
        when(topology.activeVersion()).thenReturn(java.util.Optional.of(
                new ReferenceTopologyPort.ReferenceTopologyVersion(java.util.UUID.randomUUID(), "checksum")));
        when(configuration.monitoredServices()).thenReturn(List.of("190", "972"));
        when(configuration.staleAfterSeconds()).thenReturn(180);

        service.poll();

        verify(normalized).saveAll(argThat(values -> {
            assertThat(values).extracting(BusArrivalObservation::getServiceId)
                    .containsExactly("190", "972");
            return true;
        }));
    }

    @Test
    void missingProviderDirectionUsesRouteTopologyAndStoresVisitNumber() {
        UUID referenceVersionId = UUID.randomUUID();
        when(targets.findAll()).thenReturn(List.of(target(true)));
        when(topology.activeVersion()).thenReturn(java.util.Optional.of(
                new ReferenceTopologyPort.ReferenceTopologyVersion(referenceVersionId, "checksum")));
        when(arrivals.arrivalsAt("44009", "190")).thenReturn(new BusArrivalSnapshot(200, "{}", List.of(
                new BusArrivalPrediction("190", "SBST", null, 1, "44009", "45009", now, true,
                        BigDecimal.valueOf(1.3), BigDecimal.valueOf(103.8), 7, "SEA", "WAB", "DD")), List.of()));
        when(configuration.monitoredServices()).thenReturn(List.of("190"));
        when(configuration.staleAfterSeconds()).thenReturn(180);
        when(routeStops.findByIdReferenceVersionIdAndIdServiceIdAndIdOperatorOrderByIdDirectionIdAscIdStopSequenceAsc(
                referenceVersionId, "190", "SBST")).thenReturn(List.of(
                routeStop(referenceVersionId, 1, 1, "44009"), routeStop(referenceVersionId, 1, 2, "45009")));

        service.poll();

        verify(normalized).saveAll(argThat(values -> {
            assertThat(values).singleElement().satisfies(value -> {
                assertThat(value.getDirectionId()).isEqualTo(1);
                assertThat(value.getVisitNumber()).isEqualTo(7);
            });
            return true;
        }));
    }

    @Test
    void ambiguousRouteDirectionUsesMatchingTerminalsAndHandlesMissingOperator() {
        UUID referenceVersionId = UUID.randomUUID();
        when(targets.findAll()).thenReturn(List.of(target(true)));
        when(topology.activeVersion()).thenReturn(java.util.Optional.of(
                new ReferenceTopologyPort.ReferenceTopologyVersion(referenceVersionId, "checksum")));
        when(configuration.monitoredServices()).thenReturn(List.of("190"));
        when(configuration.staleAfterSeconds()).thenReturn(180);
        when(routeStops.findByIdReferenceVersionIdAndIdServiceIdAndIdOperatorOrderByIdDirectionIdAscIdStopSequenceAsc(
                referenceVersionId, "190", "SBST")).thenReturn(List.of(
                routeStop(referenceVersionId, 1, 1, "100"), routeStop(referenceVersionId, 1, 2, "44009"),
                routeStop(referenceVersionId, 1, 3, "200"), routeStop(referenceVersionId, 2, 1, "300"),
                routeStop(referenceVersionId, 2, 2, "44009"), routeStop(referenceVersionId, 2, 3, "400")));
        when(arrivals.arrivalsAt("44009", "190")).thenReturn(new BusArrivalSnapshot(200, "{}", List.of(
                directionless("SBST", "100", "200"), directionless("SBST", null, "200"),
                directionless("SBST", "100", null), directionless("SBST", "missing", "200"),
                directionless("SBST", "100", "missing"), directionless(null, null, null),
                directionless("   ", null, null)), List.of()));

        service.poll();

        verify(routeStops, times(1)).findByIdReferenceVersionIdAndIdServiceIdAndIdOperatorOrderByIdDirectionIdAscIdStopSequenceAsc(
                referenceVersionId, "190", "SBST");
        verify(normalized).saveAll(argThat(values -> {
            List<BusArrivalObservation> saved = java.util.stream.StreamSupport
                    .stream(values.spliterator(), false).toList();
            assertThat(values).extracting(BusArrivalObservation::getDirectionId)
                    .containsExactly(1, null, null, null, null, null, null);
            assertThat(saved.get(5).getOperator()).isNull();
            assertThat(saved.get(6).getOperator()).isNull();
            return true;
        }));
    }

    private BusArrivalSnapshot snapshot() {
        return new BusArrivalSnapshot(200, "{}", List.of(), List.of());
    }

    private BusArrivalPrediction prediction(String serviceId) {
        return new BusArrivalPrediction(serviceId, "SBST", 1, 1, "44009", "45009", now, true,
                BigDecimal.valueOf(1.3), BigDecimal.valueOf(103.8), 1, "SEA", "WAB", "DD");
    }

    private BusArrivalPrediction directionless(String operator, String origin, String destination) {
        return new BusArrivalPrediction("190", operator, null, 1, origin, destination, now, true,
                BigDecimal.valueOf(1.3), BigDecimal.valueOf(103.8), 1, "SEA", "WAB", "DD");
    }

    private PollTarget target(boolean enabled) {
        PollTarget target = new PollTarget();
        target.setSource("DATAMALL");
        target.setScopeKey("stop:44009");
        target.setStopCode("44009");
        target.setServiceId("190");
        target.setEnabled(enabled);
        return target;
    }

    private BusRouteStop routeStop(UUID referenceVersionId, int directionId, int sequence, String stopCode) {
        return new BusRouteStop(new BusRouteStopId(referenceVersionId, "190", "SBST", (short) directionId, sequence),
                stopCode, BigDecimal.ZERO);
    }
}
