package com.csd.occ.planning.advisory.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpAdvisoryContextAdapterTest {
    @Test
    void readsTheCurrentResolvedGroupFromIngestion() {
        UUID runId = UUID.randomUUID();
        UUID episodeId = UUID.randomUUID();
        UUID snapshot = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ingestion.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(request -> assertThat(request.getURI().getPath()).isEqualTo(
                "/internal/v1/ingestion/demo-runs/" + runId + "/bunching-groups/" + episodeId + "/current"))
                .andRespond(withSuccess("""
                        {"demoRunId":"%s","bunchingEventId":"%s","serviceId":"190","stopCode":"44251",
                         "directionId":1,"rawObservationId":"%s","fetchedAt":"2026-09-28T00:00:00Z",
                         "validUntil":"2026-09-28T00:01:00Z","affectedBusReferences":["bus-1","bus-2"],"adjacentGapSeconds":[37]}
                        """.formatted(runId, episodeId, snapshot), MediaType.APPLICATION_JSON));

        var group = new HttpAdvisoryContextAdapter(null, builder.build(), null).currentBunchingGroup(runId, episodeId);

        assertThat(group.rawObservationId()).isEqualTo(snapshot);
        assertThat(group.validUntil()).isEqualTo(Instant.parse("2026-09-28T00:01:00Z"));
        assertThat(group.affectedBusReferences()).containsExactly("bus-1", "bus-2");
        assertThat(group.adjacentGapSeconds()).containsExactly(37L);
        server.verify();
    }

    @Test
    void readsPredictionTimingFromIngestionFleet() {
        UUID runId = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ingestion.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/internal/v1/ingestion/demo-fleet"))
                .andRespond(withSuccess("""
                        {"demoRunId":"%s","status":"ACTIVE","totalElements":1,"items":[{
                          "busId":"BUS-1","tripId":"TRIP-1","registrationNumber":"BUS-1",
                          "serviceId":"190","directionId":1,"assignmentStatus":"ASSIGNED",
                          "progress":{"stopCode":"44251","stopSequence":4,
                            "observedAt":"2026-09-28T00:00:00Z","assumedSpeedKmh":30,
                            "allowedSpeedReductionsKmh":[],"allowedDurationsSeconds":[],
                            "estimatedArrivalAt":"2026-09-28T00:02:00Z","freshUntil":"2026-09-28T00:01:00Z"}}]}
                        """.formatted(runId), MediaType.APPLICATION_JSON));

        var progress = new HttpAdvisoryContextAdapter(null, builder.build(), null).fleet(runId).items().getFirst().progress();

        assertThat(progress.estimatedArrivalAt()).isEqualTo(Instant.parse("2026-09-28T00:02:00Z"));
        assertThat(progress.freshUntil()).isEqualTo(Instant.parse("2026-09-28T00:01:00Z"));
        server.verify();
    }
}
