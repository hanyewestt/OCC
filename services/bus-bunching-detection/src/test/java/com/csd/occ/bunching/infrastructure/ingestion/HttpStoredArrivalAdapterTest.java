package com.csd.occ.bunching.infrastructure.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.csd.occ.bunching.domain.BunchingObservationRepository;
import com.csd.occ.bunching.domain.IngestionFeedCursorRepository;
import com.csd.occ.bunching.domain.SourceProcessingAuditRepository;
import com.csd.occ.bunching.domain.SourceRejectionRepository;
import com.csd.occ.bunching.domain.SourceRetryRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpStoredArrivalAdapterTest {
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
}
