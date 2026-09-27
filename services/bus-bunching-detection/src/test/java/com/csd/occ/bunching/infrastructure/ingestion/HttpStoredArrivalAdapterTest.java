package com.csd.occ.bunching.infrastructure.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.csd.occ.bunching.domain.BunchingObservationRepository;
import com.csd.occ.bunching.domain.ConsumerSelection;
import com.csd.occ.bunching.domain.ConsumerSelectionService;
import com.csd.occ.bunching.domain.IngestionFeedCursor;
import com.csd.occ.bunching.domain.IngestionFeedCursorRepository;
import com.csd.occ.bunching.domain.IngestionFeedHeadPort;
import com.csd.occ.bunching.domain.SourceProcessingAuditRepository;
import com.csd.occ.bunching.domain.SourceRejectionRepository;
import com.csd.occ.bunching.domain.SourceRetryRepository;
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
    void terminalOutcomesRemoveAnExistingRetry() {
        SourceRetryRepository retries = mock(SourceRetryRepository.class);
        HttpStoredArrivalAdapter adapter = new HttpStoredArrivalAdapter(RestClient.create(),
                mock(IngestionFeedCursorRepository.class), retries, mock(SourceRejectionRepository.class),
                mock(SourceProcessingAuditRepository.class), mock(BunchingObservationRepository.class));
        UUID completed = UUID.randomUUID();
        UUID rejected = UUID.randomUUID();

        adapter.complete(completed);
        adapter.reject(rejected, Instant.parse("2026-09-25T00:00:00Z"), "INVALID_SNAPSHOT");

        verify(retries).deleteById(completed);
        verify(retries).deleteById(rejected);
    }

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

        HttpStoredArrivalAdapter adapter = new HttpStoredArrivalAdapter(builder.build(),
                mock(IngestionFeedCursorRepository.class), mock(SourceRetryRepository.class),
                mock(SourceRejectionRepository.class), mock(SourceProcessingAuditRepository.class),
                mock(BunchingObservationRepository.class));
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
        var selected = mock(ConsumerSelection.class);
        when(selected.mode()).thenReturn("DEMO");
        when(selected.demoRunId()).thenReturn(demoRunId);
        var selection = mock(ConsumerSelectionService.class);
        when(selection.current()).thenReturn(selected);

        var cursors = mock(IngestionFeedCursorRepository.class);
        var cursor = mock(IngestionFeedCursor.class);
        when(cursors.findById(1)).thenReturn(Optional.of(cursor));
        when(cursor.matches("DEMO:" + demoRunId, demoRunId)).thenReturn(false);
        var retries = mock(SourceRetryRepository.class);
        when(retries.findByFeedScopeAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                eq("DEMO:" + demoRunId), eq(NOW), any(Pageable.class))).thenReturn(List.of());
        var rejections = mock(SourceRejectionRepository.class);
        when(rejections.existsById(rawId)).thenReturn(false);
        var observations = mock(BunchingObservationRepository.class);
        when(observations.findByRawObservationId(rawId)).thenReturn(Optional.empty());

        RestClient.Builder builder = RestClient.builder().baseUrl("http://ingestion.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://ingestion.test/internal/v1/demo-runs/" + demoRunId
                        + "/arrival-snapshots?afterSequence=0&size=100"))
                .andRespond(withSuccess(feedPage(rawId, pollId), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://ingestion.test/internal/v1/demo-runs/" + demoRunId
                        + "/arrival-snapshots/" + rawId))
                .andRespond(withSuccess(snapshot(rawId, pollId), MediaType.APPLICATION_JSON));
        var adapter = new HttpStoredArrivalAdapter(builder.build(), cursors, retries, rejections,
                mock(SourceProcessingAuditRepository.class), observations, selection, () -> 0);

        var ids = adapter.pendingIds(100, NOW);
        var source = adapter.snapshot(ids.getFirst());

        assertEquals(List.of(rawId), ids);
        assertEquals("SIMULATION", source.classification());
        assertEquals(demoRunId, source.demoRunId());
        verify(cursor).selectDemo(demoRunId);
        server.verify();
    }

    @Test
    void liveSelectionReadsOnlyTheLivePageAndSnapshot() {
        UUID rawId = UUID.randomUUID();
        UUID pollId = UUID.randomUUID();
        var selected = mock(ConsumerSelection.class);
        when(selected.mode()).thenReturn("LIVE");
        var selection = mock(ConsumerSelectionService.class);
        when(selection.current()).thenReturn(selected);

        var cursors = mock(IngestionFeedCursorRepository.class);
        var cursor = mock(IngestionFeedCursor.class);
        when(cursors.findById(1)).thenReturn(Optional.of(cursor));
        when(cursor.matches("LIVE", null)).thenReturn(false);
        var retries = mock(SourceRetryRepository.class);
        when(retries.findByFeedScopeAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                eq("LIVE"), eq(NOW), any(Pageable.class))).thenReturn(List.of());
        var rejections = mock(SourceRejectionRepository.class);
        when(rejections.existsById(rawId)).thenReturn(false);
        var observations = mock(BunchingObservationRepository.class);
        when(observations.findByRawObservationId(rawId)).thenReturn(Optional.empty());

        RestClient.Builder builder = RestClient.builder().baseUrl("http://ingestion.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://ingestion.test/internal/v1/arrival-snapshots?afterSequence=0&size=100"))
                .andRespond(withSuccess(feedPage(rawId, pollId), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://ingestion.test/internal/v1/arrival-snapshots/" + rawId))
                .andRespond(withSuccess(snapshot(rawId, pollId), MediaType.APPLICATION_JSON));
        var adapter = new HttpStoredArrivalAdapter(builder.build(), cursors, retries, rejections,
                mock(SourceProcessingAuditRepository.class), observations, selection, () -> 1);

        var ids = adapter.pendingIds(100, NOW);
        var source = adapter.snapshot(ids.getFirst());

        assertEquals(List.of(rawId), ids);
        assertEquals("OBSERVATION_ONLY", source.classification());
        assertNull(source.demoRunId());
        verify(cursor).selectLive(1);
        server.verify();
    }

    private static String feedPage(UUID rawId, UUID pollId) {
        return """
                {"items":[{"sequence":1,"rawObservationId":"%s","pollRunId":"%s",
                "sourceKey":"fixture","fetchedAt":"2026-09-26T05:00:00Z",
                "normalizationState":"NORMALIZED","arrivals":[]}],"nextSequence":1}
                """.formatted(rawId, pollId);
    }

    private static String snapshot(UUID rawId, UUID pollId) {
        return """
                {"sequence":1,"rawObservationId":"%s","pollRunId":"%s","sourceKey":"fixture",
                "fetchedAt":"2026-09-26T05:00:00Z","normalizationState":"NORMALIZED","arrivals":[]}
                """.formatted(rawId, pollId);
    }
}
