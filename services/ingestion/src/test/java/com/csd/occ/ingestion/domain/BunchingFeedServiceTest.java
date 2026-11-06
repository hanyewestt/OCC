package com.csd.occ.ingestion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BunchingFeedServiceTest {
    private static final Instant FETCHED_AT = Instant.parse("2026-10-27T03:00:00Z");

    @Test
    void snapshotCarriesPollCycleAndUniqueRouteOccurrenceEvidence() {
        UUID referenceId = UUID.randomUUID();
        Fixture fixture = fixture("190", "44009", referenceId);
        when(fixture.routeStops().findByIdReferenceVersionIdAndIdServiceIdOrderByIdOperatorAscIdDirectionIdAscIdStopSequenceAsc(
                referenceId, "190")).thenReturn(List.of(routeStop(referenceId, "190", "44009", 12)));

        BunchingFeedService.Snapshot snapshot = fixture.service().snapshot(fixture.rawId()).orElseThrow();

        assertThat(snapshot.sourcePollCycleId()).isEqualTo(fixture.sourcePollCycleId());
        assertThat(snapshot.arrivals()).singleElement().satisfies(arrival -> {
            assertThat(arrival.operator()).isEqualTo("SMRT");
            assertThat(arrival.visitNumber()).isEqualTo(1);
            assertThat(arrival.routeStopSequence()).isEqualTo(12);
        });
    }

    @Test
    void repeatedCircularStopOccurrenceRemainsAmbiguous() {
        UUID referenceId = UUID.randomUUID();
        Fixture fixture = fixture("972", "03239", referenceId);
        when(fixture.routeStops().findByIdReferenceVersionIdAndIdServiceIdOrderByIdOperatorAscIdDirectionIdAscIdStopSequenceAsc(
                referenceId, "972")).thenReturn(List.of(
                        routeStop(referenceId, "972", "03239", 1),
                        routeStop(referenceId, "972", "03239", 42)));

        BunchingFeedService.Snapshot snapshot = fixture.service().snapshot(fixture.rawId()).orElseThrow();

        assertThat(snapshot.arrivals()).singleElement()
                .extracting(BunchingFeedService.Arrival::routeStopSequence)
                .isNull();
    }

    private Fixture fixture(String serviceId, String stopCode, UUID referenceId) {
        UUID rawId = UUID.randomUUID();
        UUID pollRunId = UUID.randomUUID();
        UUID sourcePollCycleId = UUID.randomUUID();
        RawObservationRepository rawObservations = mock(RawObservationRepository.class);
        BusArrivalObservationRepository arrivals = mock(BusArrivalObservationRepository.class);
        PollRunRepository pollRuns = mock(PollRunRepository.class);
        BusRouteStopRepository routeStops = mock(BusRouteStopRepository.class);

        RawObservation source = mock(RawObservation.class);
        when(source.getId()).thenReturn(rawId);
        when(source.getPollRunId()).thenReturn(pollRunId);
        when(source.getFeedSequence()).thenReturn(7L);
        when(source.getSourceKey()).thenReturn("stop:" + stopCode);
        when(source.getFetchedAt()).thenReturn(FETCHED_AT);
        when(rawObservations.findBunchingFeedById(rawId)).thenReturn(Optional.of(source));

        BusArrivalObservation arrival = new BusArrivalObservation();
        arrival.setId(UUID.randomUUID());
        arrival.setRawObservationId(rawId);
        arrival.setPollRunId(pollRunId);
        arrival.setReferenceVersionId(referenceId);
        arrival.setStopCode(stopCode);
        arrival.setServiceId(serviceId);
        arrival.setOperator("SMRT");
        arrival.setDirectionId(1);
        arrival.setVisitOrder(1);
        arrival.setVisitNumber(1);
        arrival.setEstimatedArrivalAt(FETCHED_AT.plusSeconds(300));
        arrival.setOriginCode(stopCode);
        arrival.setDestinationCode("02089");
        arrival.setLatitude(new BigDecimal("1.300000"));
        arrival.setLongitude(new BigDecimal("103.800000"));
        when(arrivals.findByRawObservationIdIn(List.of(rawId))).thenReturn(List.of(arrival));

        PollRun pollRun = new PollRun();
        pollRun.setId(pollRunId);
        pollRun.setRoutePositionPollCycleId(sourcePollCycleId);
        when(pollRuns.findAllById(List.of(pollRunId))).thenReturn(List.of(pollRun));

        return new Fixture(new BunchingFeedService(rawObservations, arrivals, pollRuns, routeStops), routeStops,
                rawId, sourcePollCycleId);
    }

    private BusRouteStop routeStop(UUID referenceId, String serviceId, String stopCode, int sequence) {
        return new BusRouteStop(new BusRouteStopId(referenceId, serviceId, "SMRT", (short) 1, sequence),
                stopCode, BigDecimal.valueOf(sequence));
    }

    private record Fixture(BunchingFeedService service, BusRouteStopRepository routeStops, UUID rawId,
                           UUID sourcePollCycleId) { }
}
