package com.csd.occ.planning.diagnosis.infrastructure.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.csd.occ.planning.diagnosis.domain.BunchingEventFacts;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

class HttpBunchingContextEvidenceAdapterTest {
    @Test
    void requestsContextAtTheLatestConfirmingObservationTime() {
        Instant firstConfirmation = Instant.parse("2026-09-27T23:55:00Z");
        Instant lastConfirmation = Instant.parse("2026-09-28T00:00:00Z");
        UUID runId = UUID.randomUUID();
        List<UUID> observations = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        BunchingEventFacts event = new BunchingEventFacts(UUID.randomUUID(), UUID.randomUUID(), "SIMULATION", runId,
                "190", 1, "44251", observations.get(2), observations.get(3), "CONFIRMED", 600,
                List.of(360, 390), firstConfirmation, List.of(firstConfirmation, lastConfirmation), observations,
                List.of("bunching-event", "arrival-1", "arrival-2", "arrival-3", "arrival-4"), "bunching-v1");

        RestClient.Builder builder = RestClient.builder().baseUrl("http://ingestion.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String arrivals = observations.stream()
                .map(id -> "{\"evidenceId\":\"bus-arrival:" + id + "\",\"observationId\":\"" + id + "\"}")
                .collect(java.util.stream.Collectors.joining(","));
        server.expect(request -> {
            var parameters = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
            assertThat(parameters.getFirst("at")).isEqualTo(lastConfirmation.toString());
            assertThat(parameters.get("arrivalObservationIds")).containsExactlyElementsOf(
                    observations.stream().map(UUID::toString).toList());
        }).andRespond(withSuccess("""
                {"classification":"SIMULATION","demoRunId":"%s","referenceVersionId":null,
                 "arrivals":[%s],"incidents":[],"missingFields":[]}
                """.formatted(runId, arrivals), MediaType.APPLICATION_JSON));

        var context = new HttpBunchingContextEvidenceAdapter(builder.build()).findContext(event);

        assertThat(context.demoRunId()).isEqualTo(runId);
        server.verify();
    }
}
