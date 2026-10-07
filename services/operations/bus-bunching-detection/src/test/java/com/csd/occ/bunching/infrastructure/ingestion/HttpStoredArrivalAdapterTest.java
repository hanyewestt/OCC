package com.csd.occ.bunching.infrastructure.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.csd.occ.bunching.domain.ConsumerState;
import com.csd.occ.bunching.domain.ConsumerStateRepository;
import com.csd.occ.bunching.domain.ConsumerSelectionService;
import com.csd.occ.bunching.domain.IngestionFeedHeadPort;
import com.csd.occ.bunching.domain.SourceProcessingStateRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class HttpStoredArrivalAdapterTest {
    private static final Instant NOW = Instant.parse("2026-09-26T05:00:00Z");

    @Test
    void mapsTheIngestionSnapshotContractIntoDetectorInput() {
        UUID raw = UUID.randomUUID();
        UUID poll = UUID.randomUUID();
        UUID reference = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ingestion.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://ingestion.test/internal/v1/arrival-snapshots/" + raw))
                .andRespond(withSuccess("""
                        {"sequence":7,"rawObservationId":"%s","pollRunId":"%s","sourceKey":"stop:44009",
                         "fetchedAt":"2026-09-25T00:00:00Z","normalizationState":"NORMALIZED","arrivals":[
                         {"observationId":"%s","referenceVersionId":"%s","stopCode":"44009","serviceId":"190",
                          "directionId":1,"visitOrder":1,"estimatedArrivalAt":"2026-09-25T00:05:00Z"},
                         {"observationId":"%s","referenceVersionId":"%s","stopCode":"44009","serviceId":"190",
                          "directionId":1,"visitOrder":2,"estimatedArrivalAt":"2026-09-25T00:06:00Z"}]}
                        """.formatted(raw, poll, first, reference, second, reference), MediaType.APPLICATION_JSON));

        HttpStoredArrivalAdapter adapter = adapter(builder.build(), "LIVE", null);
        var snapshot = adapter.snapshot(raw);

        assertEquals(raw, snapshot.rawObservationId());
        assertEquals(poll, snapshot.pollRunId());
        assertEquals(reference, snapshot.referenceVersionId().orElseThrow());
        assertEquals(2, snapshot.arrivals().size());
        assertEquals(Instant.parse("2026-09-25T00:05:00Z"), snapshot.arrivals().getFirst().estimatedArrivalAt().orElseThrow());
        assertTrue(snapshot.arrivals().stream().allMatch(arrival -> arrival.directionId().orElseThrow() == 1));
        server.verify();
    }

    @Test
    void demoSelectionReadsOnlyItsRunScopedPageAndSnapshot() {
        UUID demoRunId = UUID.randomUUID();
        UUID rawId = UUID.randomUUID();
        UUID pollId = UUID.randomUUID();
        var selected = mock(ConsumerState.class);
        when(selected.mode()).thenReturn("DEMO");
        when(selected.demoRunId()).thenReturn(demoRunId);
        var selection = mock(ConsumerSelectionService.class);
        when(selection.current()).thenReturn(selected);

        var consumers = mock(ConsumerStateRepository.class);
        when(consumers.findWithLockById(1)).thenReturn(Optional.of(selected));
        when(selected.matches("DEMO", demoRunId)).thenReturn(true); when(selected.lastSequence()).thenReturn(0L);
        var processing = mock(SourceProcessingStateRepository.class);
        when(processing.findByDemoRunIdAndStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(eq(demoRunId), eq("DEFERRED"), eq(NOW), any(Pageable.class))).thenReturn(List.of());

        RestClient.Builder builder = RestClient.builder().baseUrl("http://ingestion.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://ingestion.test/internal/v1/demo-runs/" + demoRunId
                        + "/arrival-snapshots?afterSequence=0&size=100"))
                .andRespond(withSuccess(feedPage(rawId, pollId), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://ingestion.test/internal/v1/demo-runs/" + demoRunId
                        + "/arrival-snapshots/" + rawId))
                .andRespond(withSuccess(snapshot(rawId, pollId), MediaType.APPLICATION_JSON));
        var adapter = new HttpStoredArrivalAdapter(builder.build(), consumers, processing, selection, () -> 0);

        var ids = adapter.pendingIds(100, NOW);
        var source = adapter.snapshot(ids.getFirst());

        assertEquals(List.of(rawId), ids);
        assertEquals("SIMULATION", source.classification());
        assertEquals(demoRunId, source.demoRunId());
        server.verify();
    }

    @Test
    void liveSelectionReadsOnlyTheLivePageAndSnapshot() {
        UUID rawId = UUID.randomUUID();
        UUID pollId = UUID.randomUUID();
        var selected = mock(ConsumerState.class);
        when(selected.mode()).thenReturn("LIVE");
        var selection = mock(ConsumerSelectionService.class);
        when(selection.current()).thenReturn(selected);

        var consumers = mock(ConsumerStateRepository.class);
        when(consumers.findWithLockById(1)).thenReturn(Optional.of(selected));
        when(selected.matches("LIVE", null)).thenReturn(true); when(selected.lastSequence()).thenReturn(0L);
        var processing = mock(SourceProcessingStateRepository.class);
        when(processing.findByDemoRunIdIsNullAndStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(eq("DEFERRED"), eq(NOW), any(Pageable.class))).thenReturn(List.of());

        RestClient.Builder builder = RestClient.builder().baseUrl("http://ingestion.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://ingestion.test/internal/v1/arrival-snapshots?afterSequence=0&size=100"))
                .andRespond(withSuccess(feedPage(rawId, pollId), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://ingestion.test/internal/v1/arrival-snapshots/" + rawId))
                .andRespond(withSuccess(snapshot(rawId, pollId), MediaType.APPLICATION_JSON));
        var adapter = new HttpStoredArrivalAdapter(builder.build(), consumers, processing, selection, () -> 1);

        var ids = adapter.pendingIds(100, NOW);
        var source = adapter.snapshot(ids.getFirst());

        assertEquals(List.of(rawId), ids);
        assertEquals("OBSERVATION_ONLY", source.classification());
        assertNull(source.demoRunId());
        server.verify();
    }

    private static String feedPage(UUID rawId, UUID pollId) {
        return """
                {"items":[{"sequence":1,"rawObservationId":"%s","pollRunId":"%s",
                "sourceKey":"fixture","fetchedAt":"2026-09-26T05:00:00Z",
                "normalizationState":"NORMALIZED","arrivals":[]}],"nextSequence":1}
                """.formatted(rawId, pollId);
    }

    private static HttpStoredArrivalAdapter adapter(RestClient client, String mode, UUID runId) {
        var consumers = mock(ConsumerStateRepository.class);
        var state = mock(ConsumerState.class);
        when(consumers.findWithLockById(1)).thenReturn(Optional.of(state));
        when(state.matches(mode, runId)).thenReturn(true);
        var selection = mock(ConsumerSelectionService.class);
        when(selection.current()).thenReturn(state);
        when(state.mode()).thenReturn(mode); when(state.demoRunId()).thenReturn(runId);
        var processing = mock(SourceProcessingStateRepository.class);
        return new HttpStoredArrivalAdapter(client, consumers, processing, selection, () -> 0);
    }

    private static String snapshot(UUID rawId, UUID pollId) {
        return """
                {"sequence":1,"rawObservationId":"%s","pollRunId":"%s","sourceKey":"fixture",
                "fetchedAt":"2026-09-26T05:00:00Z","normalizationState":"NORMALIZED","arrivals":[]}
                """.formatted(rawId, pollId);
    }
}
