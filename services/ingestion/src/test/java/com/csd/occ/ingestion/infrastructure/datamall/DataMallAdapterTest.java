package com.csd.occ.ingestion.infrastructure.datamall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.csd.occ.ingestion.infrastructure.config.DataMallProperties;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class DataMallAdapterTest {
    @Test
    void referenceImportPaginatesAndPreservesBusRouteTopology() {
        AtomicReference<MockRestServiceServer> server = new AtomicReference<>();
        DataMallAdapter adapter = adapter(server);
        server.get().expect(requestTo("https://example.test/BusStops?$skip=0"))
                .andExpect(header("AccountKey", "synthetic-key"))
                .andRespond(withSuccess("{\"value\":[{\"BusStopCode\":\"44009\",\"RoadName\":\"Road\",\"Description\":\"Stop\",\"Latitude\":\"1.3\",\"Longitude\":\"103.8\"}]}", MediaType.APPLICATION_JSON));
        server.get().expect(requestTo("https://example.test/BusStops?$skip=500"))
                .andRespond(withSuccess("{\"value\":[]}", MediaType.APPLICATION_JSON));
        server.get().expect(requestTo("https://example.test/BusServices?$skip=0"))
                .andRespond(withSuccess("{\"value\":[{\"ServiceNo\":\"190\",\"Operator\":\"SBST\",\"Category\":\"TRUNK\"}]}", MediaType.APPLICATION_JSON));
        server.get().expect(requestTo("https://example.test/BusServices?$skip=500"))
                .andRespond(withSuccess("{\"value\":[]}", MediaType.APPLICATION_JSON));
        server.get().expect(requestTo("https://example.test/BusRoutes?$skip=0"))
                .andRespond(withSuccess("{\"value\":[{\"ServiceNo\":\"190\",\"Operator\":\"SBST\",\"Direction\":1,\"StopSequence\":1,\"BusStopCode\":\"44009\",\"Distance\":\"0\"}]}", MediaType.APPLICATION_JSON));
        server.get().expect(requestTo("https://example.test/BusRoutes?$skip=500"))
                .andRespond(withSuccess("{\"value\":[]}", MediaType.APPLICATION_JSON));
        var data = adapter.fetch();
        assertThat(data.stops()).singleElement().extracting(stop -> stop.stopCode()).isEqualTo("44009");
        assertThat(data.routeStops()).singleElement().extracting(route -> route.serviceId()).isEqualTo("190");
        server.get().verify();
    }

    @Test
    void arrivalsPreserveRawResponseAndAuthenticationHeader() {
        AtomicReference<MockRestServiceServer> server = new AtomicReference<>();
        DataMallAdapter adapter = adapter(server);
        server.get().expect(requestTo("https://example.test/v3/BusArrival?BusStopCode=44009&ServiceNo=190"))
                .andExpect(header("AccountKey", "synthetic-key"))
                .andRespond(withSuccess("{\"Services\":[]}", MediaType.APPLICATION_JSON));
        var response = adapter.arrivalsAt("44009", "190");
        assertThat(response.httpStatus()).isEqualTo(200);
        assertThat(response.payload()).isEqualTo("{\"Services\":[]}");
        server.get().verify();
    }

    private DataMallAdapter adapter(AtomicReference<MockRestServiceServer> server) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://example.test");
        server.set(MockRestServiceServer.bindTo(builder).build());
        return new DataMallAdapter(builder.build(),
                new DataMallProperties(URI.create("https://example.test"), "synthetic-key",
                        Duration.ofSeconds(1), Duration.ofSeconds(2)),
                JsonMapper.builder().findAndAddModules().build());
    }
}
