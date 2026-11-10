package com.csd.occ.ingestion.infrastructure.bunching;

import static org.assertj.core.api.Assertions.assertThat;

import com.csd.occ.ingestion.infrastructure.config.DemoProperties;
import com.csd.occ.ingestion.infrastructure.security.PeerClientProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HttpBunchingControlAdapterTest {
    @Test
    void readsCurrentEvidenceWithTheIngestionPeerCapability() throws Exception {
        UUID run = UUID.randomUUID();
        UUID episode = UUID.randomUUID();
        UUID observation = UUID.randomUUID();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> token = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            token.set(exchange.getRequestHeaders().getFirst("X-OCC-Peer-Token"));
            byte[] body = """
                    {"demoRunId":"%s","bunchingEventId":"%s","serviceId":"190","stopCode":"44251",
                     "directionId":1,"state":"BUNCHING","rawObservationId":"%s",
                     "fetchedAt":"2026-09-28T00:00:00Z","validUntil":"2026-09-28T00:02:00Z",
                     "fresh":true,"affectedObservationIds":["%s"]}
                    """.formatted(run, episode, UUID.randomUUID(), observation).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var settings = new DemoProperties("bunching-v1", 1, Duration.ofSeconds(1), Duration.ofSeconds(90),
                    base, Duration.ofSeconds(5), base, Duration.ofSeconds(5));
            var adapter = new HttpBunchingControlAdapter(settings, new PeerClientProperties("synthetic-peer", "synthetic-planning"));

            var evidence = adapter.currentEvidence(run, episode).orElseThrow();

            assertThat(path.get()).isEqualTo("/internal/v1/disruption/demo-runs/" + run + "/bunching-groups/" + episode + "/current-evidence");
            assertThat(token.get()).isEqualTo("synthetic-peer");
            assertThat(evidence.affectedObservationIds()).containsExactly(observation);
            assertThat(evidence.validUntil()).isEqualTo(Instant.parse("2026-09-28T00:02:00Z"));
        } finally {
            server.stop(0);
        }
    }
}
