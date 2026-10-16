package com.csd.occ.ingestion.infrastructure.datamall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.csd.occ.ingestion.infrastructure.config.DataMallProperties;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
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

    @Test
    void trafficIncidentsNormalizeStableObservationIdentityAndPreserveRawPayload() {
        AtomicReference<MockRestServiceServer> server = new AtomicReference<>();
        DataMallAdapter adapter = adapter(server);
        String payload = "[{\"Type\":\"Vehicle Breakdown\",\"Latitude\":1.30398068448214,"
                + "\"Longitude\":103.919182834377,\"Message\":\"Vehicle breakdown on ECP\"}]";
        server.get().expect(requestTo("https://example.test/TrafficIncidents"))
                .andExpect(header("AccountKey", "synthetic-key"))
                .andRespond(withSuccess(payload, MediaType.APPLICATION_JSON));

        var response = adapter.trafficIncidents();

        assertThat(response.httpStatus()).isEqualTo(200);
        assertThat(response.payload()).isEqualTo(payload);
        assertThat(response.errors()).isEmpty();
        assertThat(response.incidents()).singleElement().satisfies(incident -> {
            assertThat(incident.sourceIncidentKey()).matches("[0-9a-f]{64}");
            assertThat(incident.incidentType()).isEqualTo("Vehicle Breakdown");
            assertThat(incident.latitude()).isEqualByComparingTo("1.3039807");
            assertThat(incident.longitude()).isEqualByComparingTo("103.9191828");
            assertThat(incident.message()).isEqualTo("Vehicle breakdown on ECP");
            assertThat(incident.observedAt()).isNull();
        });
        server.get().verify();
    }

    @Test
    void trafficIncidentPartialRecordsAreReportedAsIncomplete() {
        AtomicReference<MockRestServiceServer> server = new AtomicReference<>();
        DataMallAdapter adapter = adapter(server);
        server.get().expect(requestTo("https://example.test/TrafficIncidents"))
                .andRespond(withSuccess("[{\"Type\":\"Road Works\",\"Latitude\":1.3,"
                        + "\"Longitude\":103.8,\"Message\":\"Road works\"},"
                        + "{\"Type\":\"Invalid\",\"Latitude\":999,\"Longitude\":103.8,"
                        + "\"Message\":\"Invalid coordinates\"}]", MediaType.APPLICATION_JSON));

        var response = adapter.trafficIncidents();

        assertThat(response.incidents()).hasSize(1);
        assertThat(response.errors()).hasSize(1);
        server.get().verify();
    }

    @Test
    void trafficIncidentPagesFollowProviderLimitAndRetainEachRawResponse() throws Exception {
        AtomicReference<MockRestServiceServer> server = new AtomicReference<>();
        DataMallAdapter adapter = adapter(server, 2, 3);
        String firstPage = "{\"value\":["
                + "{\"Type\":\"Accident\",\"Latitude\":1.3,\"Longitude\":103.8,\"Message\":\"Accident\"},"
                + "{\"Type\":\"Roadwork\",\"Latitude\":1.4,\"Longitude\":103.9,\"Message\":\"Roadwork\"}]}";
        String secondPage = "{\"value\":[{\"Type\":\"Obstacle\",\"Latitude\":1.5,"
                + "\"Longitude\":104.0,\"Message\":\"Obstacle\"}]}";
        server.get().expect(requestTo("https://example.test/TrafficIncidents"))
                .andRespond(withSuccess(firstPage, MediaType.APPLICATION_JSON));
        server.get().expect(requestTo("https://example.test/TrafficIncidents?$skip=2"))
                .andRespond(withSuccess(secondPage, MediaType.APPLICATION_JSON));

        var response = adapter.trafficIncidents();

        assertThat(response.errors()).isEmpty();
        assertThat(response.incidents()).hasSize(3);
        var raw = JsonMapper.builder().findAndAddModules().build().readTree(response.payload());
        assertThat(raw.path("endpoint").asText()).isEqualTo("TrafficIncidents");
        assertThat(raw.path("responses").get(0).asText()).isEqualTo(firstPage);
        assertThat(raw.path("responses").get(1).asText()).isEqualTo(secondPage);
        server.get().verify();
    }

    @Test
    void laterPageFailureRetainsRowsButMarksSnapshotIncomplete() {
        AtomicReference<MockRestServiceServer> server = new AtomicReference<>();
        DataMallAdapter adapter = adapter(server, 1, 3);
        String firstPage = "{\"value\":[{\"Type\":\"Accident\",\"Latitude\":1.3,"
                + "\"Longitude\":103.8,\"Message\":\"Accident\"}]}";
        server.get().expect(requestTo("https://example.test/TrafficIncidents"))
                .andRespond(withSuccess(firstPage, MediaType.APPLICATION_JSON));
        server.get().expect(requestTo("https://example.test/TrafficIncidents?$skip=1"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("temporarily unavailable"));

        var response = adapter.trafficIncidents();

        assertThat(response.incidents()).hasSize(1);
        assertThat(response.payload()).isEqualTo(firstPage);
        assertThat(response.errors()).contains("DataMall traffic incident page 1 returned HTTP 503");
        server.get().verify();
    }

    @Test
    void fullLastPageAtConfiguredMaximumIsIncomplete() {
        AtomicReference<MockRestServiceServer> server = new AtomicReference<>();
        DataMallAdapter adapter = adapter(server, 2, 1);
        String payload = "{\"value\":["
                + "{\"Type\":\"Accident\",\"Latitude\":1.3,\"Longitude\":103.8,\"Message\":\"Accident\"},"
                + "{\"Type\":\"Roadwork\",\"Latitude\":1.4,\"Longitude\":103.9,\"Message\":\"Roadwork\"}]}";
        server.get().expect(requestTo("https://example.test/TrafficIncidents"))
                .andRespond(withSuccess(payload, MediaType.APPLICATION_JSON));

        var response = adapter.trafficIncidents();

        assertThat(response.httpStatus()).isEqualTo(200);
        assertThat(response.payload()).isEqualTo(payload);
        assertThat(response.incidents()).hasSize(2);
        assertThat(response.errors()).contains("DataMall traffic incident feed exceeded the configured maximum page count");
        server.get().verify();
    }

    @Test
    void responseLargerThanConfiguredPageSizeIsIncomplete() {
        AtomicReference<MockRestServiceServer> server = new AtomicReference<>();
        DataMallAdapter adapter = adapter(server, 1, 3);
        String payload = "{\"value\":["
                + "{\"Type\":\"Accident\",\"Latitude\":1.3,\"Longitude\":103.8,\"Message\":\"Accident\"},"
                + "{\"Type\":\"Roadwork\",\"Latitude\":1.4,\"Longitude\":103.9,\"Message\":\"Roadwork\"}]}";
        server.get().expect(requestTo("https://example.test/TrafficIncidents"))
                .andRespond(withSuccess(payload, MediaType.APPLICATION_JSON));

        var response = adapter.trafficIncidents();

        assertThat(response.errors()).contains("DataMall traffic incident response exceeded the configured page size");
        server.get().verify();
    }

    @Test
    void responseAtConfiguredByteLimitIsAccepted() {
        AtomicReference<MockRestServiceServer> server = new AtomicReference<>();
        DataMallAdapter adapter = adapter(server, 500, 10, 1_024);
        String prefix = "{\"Services\":[],\"padding\":\"";
        String suffix = "\"}";
        int paddingBytes = 1_024 - prefix.length() - suffix.length();
        String payload = prefix + "é".repeat(paddingBytes / 2) + "a".repeat(paddingBytes % 2) + suffix;
        assertThat(payload.getBytes(StandardCharsets.UTF_8)).hasSize(1_024);
        server.get().expect(requestTo("https://example.test/v3/BusArrival?BusStopCode=44009&ServiceNo=190"))
                .andRespond(withSuccess(payload, MediaType.APPLICATION_JSON));

        var response = adapter.arrivalsAt("44009", "190");

        assertThat(response.payload()).isEqualTo(payload);
        assertThat(response.predictions()).isEmpty();
        assertThat(response.parseErrors()).isEmpty();
        server.get().verify();
    }

    @Test
    void responseOverConfiguredByteLimitFailsBeforeParsingOrPersistence() {
        AtomicReference<MockRestServiceServer> server = new AtomicReference<>();
        DataMallAdapter adapter = adapter(server, 500, 10, 1_024);
        server.get().expect(requestTo("https://example.test/v3/BusArrival?BusStopCode=44009&ServiceNo=190"))
                .andRespond(withSuccess("x".repeat(1_025), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.arrivalsAt("44009", "190"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DataMall response exceeded configured maximum of 1024 bytes");
        server.get().verify();
    }

    @Test
    void oversizedLaterIncidentPageKeepsEarlierRowsAndMarksSnapshotIncomplete() {
        AtomicReference<MockRestServiceServer> server = new AtomicReference<>();
        DataMallAdapter adapter = adapter(server, 1, 3, 1_024);
        String firstPage = "[{\"Type\":\"Accident\",\"Latitude\":1.3,"
                + "\"Longitude\":103.8,\"Message\":\"Accident\"}]";
        server.get().expect(requestTo("https://example.test/TrafficIncidents"))
                .andRespond(withSuccess(firstPage, MediaType.APPLICATION_JSON));
        server.get().expect(requestTo("https://example.test/TrafficIncidents?$skip=1"))
                .andRespond(withSuccess("x".repeat(1_025), MediaType.APPLICATION_JSON));

        var response = adapter.trafficIncidents();

        assertThat(response.incidents()).hasSize(1);
        assertThat(response.payload()).isEqualTo(firstPage);
        assertThat(response.errors()).contains("DataMall traffic incident page request failed");
        server.get().verify();
    }

    private DataMallAdapter adapter(AtomicReference<MockRestServiceServer> server) {
        return adapter(server, 500, 10);
    }

    private DataMallAdapter adapter(AtomicReference<MockRestServiceServer> server, int pageSize, int maxPages) {
        return adapter(server, pageSize, maxPages, 5_000_000);
    }

    private DataMallAdapter adapter(AtomicReference<MockRestServiceServer> server, int pageSize, int maxPages,
            int maxResponseBytes) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://example.test");
        server.set(MockRestServiceServer.bindTo(builder).build());
        return new DataMallAdapter(builder.build(),
                new DataMallProperties(URI.create("https://example.test"), "synthetic-key",
                        Duration.ofSeconds(1), Duration.ofSeconds(2), pageSize, maxPages, maxResponseBytes),
                JsonMapper.builder().findAndAddModules().build());
    }
}
