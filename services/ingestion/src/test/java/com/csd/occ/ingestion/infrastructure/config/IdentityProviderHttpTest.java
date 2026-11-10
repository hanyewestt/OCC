package com.csd.occ.ingestion.infrastructure.config;

import static org.assertj.core.api.Assertions.*;

import com.csd.occ.ingestion.domain.IdentityProviderException;
import com.csd.occ.ingestion.infrastructure.supabase.SupabaseIdentityProviderAdapter;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class IdentityProviderHttpTest {
    @TempDir Path temporary;

    @Test
    void realJdkTransportSendsModernKeyOnlyAndReadsRotatedMountedSecret() throws Exception {
        var apiKey = new AtomicReference<String>();
        var bearer = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/auth/v1/admin/users", exchange -> {
            apiKey.set(exchange.getRequestHeaders().getFirst("apikey"));
            bearer.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"users\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            Path file = temporary.resolve("mounted-secret");
            Files.writeString(file, "sb_secret_synthetic_first");
            var settings = new IdentityProviderProperties(true,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), file.toString(), Duration.ofSeconds(5), Duration.ofMinutes(5));
            var client = new IdentityProviderConfig().identityProviderRestClient(RestClient.builder(), settings);
            var adapter = new SupabaseIdentityProviderAdapter(client, settings, JsonMapper.builder().build(), Clock.systemUTC());
            assertThat(adapter.listPage(1, 1000).users()).isEmpty();
            assertThat(apiKey.get()).isEqualTo("sb_secret_synthetic_first");
            assertThat(bearer.get()).isNull();
            Files.writeString(file, "sb_secret_synthetic_rotated");
            adapter.listPage(1, 1000);
            assertThat(apiKey.get()).isEqualTo("sb_secret_synthetic_rotated");
            Files.writeString(file, "invalid-private-value");
            assertThatThrownBy(() -> adapter.get(UUID.randomUUID())).isInstanceOf(IdentityProviderException.class)
                    .hasNoCause().hasMessageNotContaining("invalid-private-value");
        } finally {
            server.stop(0);
        }
    }
}
