package com.csd.occ.operations.infrastructure.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpLiveMapAdapterTest {
    @Test
    void readsStoredObservationsWithoutCallingDataMall() {
        var builder = RestClient.builder().baseUrl("http://ingestion.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://ingestion.test/api/v1/map/observed-buses?page=0&size=50&serviceId=190"))
                .andRespond(withSuccess("""
                        {"items":[],"page":0,"size":50,"totalItems":0,"totalPages":0}
                        """, MediaType.APPLICATION_JSON));
        assertThat(new HttpLiveMapAdapter(builder.build()).observations("190", 0, 50).items()).isEmpty();
        server.verify();
    }
}
