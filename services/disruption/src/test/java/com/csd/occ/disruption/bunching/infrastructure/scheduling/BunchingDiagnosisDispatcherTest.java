package com.csd.occ.disruption.bunching.infrastructure.scheduling;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.csd.occ.disruption.bunching.domain.DiagnosisOutboxClaim;
import com.csd.occ.disruption.bunching.domain.DiagnosisOutboxService;
import com.csd.occ.disruption.bunching.infrastructure.config.DiagnosisOutboxProperties;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class BunchingDiagnosisDispatcherTest {
    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");

    @Test
    void acceptedRequestMarksOutboxItemDelivered() {
        Fixture fixture = fixture(0);
        DiagnosisOutboxClaim claim = claim(1);
        when(fixture.outbox.claimDue(10, Duration.ofMinutes(1))).thenReturn(List.of(claim));
        fixture.server.expect(requestTo("http://diagnosis.test/internal/v1/planning/requests"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", claim.id().toString()))
                .andExpect(content().json("""
                        {"eventType":"BUS_BUNCHING","producer":"disruption","eventId":"%s"}
                        """.formatted(claim.eventId())))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        fixture.dispatcher.dispatchPending();

        verify(fixture.outbox).delivered(claim.id(), 202);
        fixture.server.verify();
    }

    @Test
    void transientResponseSchedulesJitteredRetry() {
        Fixture fixture = fixture(0);
        DiagnosisOutboxClaim claim = claim(1);
        when(fixture.outbox.claimDue(10, Duration.ofMinutes(1))).thenReturn(List.of(claim));
        fixture.server.expect(requestTo("http://diagnosis.test/internal/v1/planning/requests"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        fixture.dispatcher.dispatchPending();

        verify(fixture.outbox).retry(eq(claim.id()), eq(503), eq("PLANNING_REQUEST_TRANSIENT_HTTP_FAILURE"),
                argThat(retryAt -> !retryAt.isBefore(NOW.plusMillis(1_600))
                        && !retryAt.isAfter(NOW.plusMillis(2_400))));
        fixture.server.verify();
    }

    @Test
    void terminalResponseAndExhaustedTransientResponseFailOutboxItem() {
        Fixture clientError = fixture(0);
        DiagnosisOutboxClaim rejected = claim(1);
        when(clientError.outbox.claimDue(10, Duration.ofMinutes(1))).thenReturn(List.of(rejected));
        clientError.server.expect(requestTo("http://diagnosis.test/internal/v1/planning/requests"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        clientError.dispatcher.dispatchPending();

        verify(clientError.outbox).fail(rejected.id(), 400, "PLANNING_REQUEST_REJECTED_HTTP_400");
        clientError.server.verify();

        Fixture exhausted = fixture(1);
        DiagnosisOutboxClaim lastAttempt = claim(1);
        when(exhausted.outbox.claimDue(10, Duration.ofMinutes(1))).thenReturn(List.of(lastAttempt));
        exhausted.server.expect(requestTo("http://diagnosis.test/internal/v1/planning/requests"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        exhausted.dispatcher.dispatchPending();

        verify(exhausted.outbox).fail(lastAttempt.id(), 429, "PLANNING_REQUEST_TRANSIENT_HTTP_FAILURE");
        exhausted.server.verify();
    }

    @Test
    void transportFailureSchedulesRetryWithNoHttpStatus() throws IOException {
        Fixture fixture = fixture(0);
        DiagnosisOutboxClaim claim = claim(2);
        when(fixture.outbox.claimDue(10, Duration.ofMinutes(1))).thenReturn(List.of(claim));
        fixture.server.expect(requestTo("http://diagnosis.test/internal/v1/planning/requests"))
                .andRespond(request -> { throw new IOException("synthetic network failure"); });

        fixture.dispatcher.dispatchPending();

        verify(fixture.outbox).retry(eq(claim.id()), eq(null), eq("PLANNING_REQUEST_UNAVAILABLE"),
                argThat(retryAt -> !retryAt.isBefore(NOW.plusMillis(3_200))
                        && !retryAt.isAfter(NOW.plusMillis(4_800))));
        fixture.server.verify();
    }

    private Fixture fixture(int maxAttempts) {
        DiagnosisOutboxService outbox = mock(DiagnosisOutboxService.class);
        RestClient.Builder builder = RestClient.builder().baseUrl("http://diagnosis.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DiagnosisOutboxProperties properties = new DiagnosisOutboxProperties(URI.create("http://diagnosis.test"),
                true, Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofMinutes(1), 10, maxAttempts,
                Duration.ofSeconds(2), Duration.ofSeconds(60));
        BunchingDiagnosisDispatcher dispatcher = new BunchingDiagnosisDispatcher(outbox, properties,
                builder.build(), Clock.fixed(NOW, ZoneOffset.UTC));
        return new Fixture(dispatcher, outbox, server);
    }

    private DiagnosisOutboxClaim claim(int attempt) {
        return new DiagnosisOutboxClaim(UUID.randomUUID(), UUID.randomUUID(), attempt);
    }

    private record Fixture(BunchingDiagnosisDispatcher dispatcher, DiagnosisOutboxService outbox,
                           MockRestServiceServer server) { }
}
