package com.csd.occ.ingestion.infrastructure.scheduling;

import com.csd.occ.ingestion.infrastructure.config.ArrivalPollingProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import sg.edu.smu.occ.ingestion.domain.PollTarget;
import sg.edu.smu.occ.ingestion.repository.PollTargetRepository;
import sg.edu.smu.occ.ingestion.service.InitSeeder;
import java.net.URI;
import java.time.*;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

// The production seeder retains its legacy package; this test covers its HTTP boundary without live DataMall.
class InitSeederTest {
    @Test
    void traversesPagesOnceAndCollectsBothRoutesWithoutDuplicateStops() throws Exception {
        var repository = mock(PollTargetRepository.class);
        var builder = mock(RestTemplateBuilder.class);
        var http = new RestTemplate();
        when(builder.build()).thenReturn(http);
        var server = MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo("http://stub/BusRoutes?$skip=0")).andRespond(withSuccess(
                "{\"value\":[{\"ServiceNo\":\"190\",\"BusStopCode\":\"44009\"},{\"ServiceNo\":\"972\",\"BusStopCode\":\"44009\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://stub/BusRoutes?$skip=500")).andRespond(withSuccess(
                "{\"value\":[{\"ServiceNo\":\"972\",\"BusStopCode\":\"10009\"},{\"ServiceNo\":\"999\",\"BusStopCode\":\"99999\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://stub/BusRoutes?$skip=1000")).andRespond(withSuccess("{\"value\":[]}", MediaType.APPLICATION_JSON));
        var properties = new ArrivalPollingProperties(URI.create("http://stub"), "synthetic", Duration.ofSeconds(10), Duration.ofSeconds(45), 4096, 120);
        new InitSeeder(repository, builder, properties, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)).run();
        var captor = ArgumentCaptor.forClass(PollTarget.class);
        verify(repository, times(2)).save(captor.capture());
        assertEquals(Set.of("44009", "10009"), captor.getAllValues().stream().map(PollTarget::getStopCode).collect(Collectors.toSet()));
        server.verify();
    }

    @Test
    void doesNotSaveAnyTargetsWhenLaterPaginationFails() {
        var repository = mock(PollTargetRepository.class);
        var builder = mock(RestTemplateBuilder.class);
        var http = new RestTemplate();
        when(builder.build()).thenReturn(http);
        var server = MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo("http://stub/BusRoutes?$skip=0")).andRespond(withSuccess(
                "{\"value\":[{\"ServiceNo\":\"190\",\"BusStopCode\":\"44009\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://stub/BusRoutes?$skip=500")).andRespond(withServerError());
        var properties = new ArrivalPollingProperties(URI.create("http://stub"), "synthetic", Duration.ofSeconds(10), Duration.ofSeconds(45), 4096, 120);

        assertThrows(IllegalStateException.class,
                () -> new InitSeeder(repository, builder, properties, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)).run());

        verify(repository, never()).save(any());
        server.verify();
    }

    @Test
    void doesNotSaveAnyTargetsWhenALaterSuccessfulResponseHasNoValueArray() {
        var repository = mock(PollTargetRepository.class);
        var builder = mock(RestTemplateBuilder.class);
        var http = new RestTemplate();
        when(builder.build()).thenReturn(http);
        var server = MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo("http://stub/BusRoutes?$skip=0")).andRespond(withSuccess(
                "{\"value\":[{\"ServiceNo\":\"190\",\"BusStopCode\":\"44009\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://stub/BusRoutes?$skip=500")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        var properties = new ArrivalPollingProperties(URI.create("http://stub"), "synthetic", Duration.ofSeconds(10), Duration.ofSeconds(45), 4096, 120);

        assertThrows(IllegalStateException.class,
                () -> new InitSeeder(repository, builder, properties, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)).run());

        verify(repository, never()).save(any());
        server.verify();
    }
}
