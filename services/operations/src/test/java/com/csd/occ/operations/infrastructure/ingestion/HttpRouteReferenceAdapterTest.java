package com.csd.occ.operations.infrastructure.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpRouteReferenceAdapterTest {
    @Test
    void mapsIngestionRouteStopsWithoutNeedingArrivalPolls() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ingestion.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("http://ingestion.test/api/v1/bus-routes/190"))
                .andRespond(withSuccess("""
                        {"serviceId":"190","operators":[{"operator":"SBST","directions":[
                          {"directionId":1,"stops":[{"stopCode":"44251","latitude":1.3786545,
                           "longitude":103.7584354,"stopSequence":12}]}]}]}
                        """, MediaType.APPLICATION_JSON));

        var route = new HttpRouteReferenceAdapter(builder.build()).route("190").orElseThrow();

        assertThat(route.directions()).hasSize(1);
        assertThat(route.directions().getFirst().operator()).isEqualTo("SBST");
        assertThat(route.directions().getFirst().stops().getFirst().stopCode()).isEqualTo("44251");
        assertThat(route.directions().getFirst().stops().getFirst().stopSequence()).isEqualTo(12);
        server.verify();
    }
}
