package com.csd.occ.gateway.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(GatewayRoutesTest.TestDecoderConfig.class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class GatewayRoutesTest {
    private static final AtomicReference<String> lastBackendPath = new AtomicReference<>();
    private static HttpServer backend;
    private final TestRestTemplate rest = new TestRestTemplate();
    private final WebApplicationContext applicationContext;

    GatewayRoutesTest(WebApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @DynamicPropertySource
    static void setRuntimeProperties(DynamicPropertyRegistry registry) {
        startBackend();
        registry.add("OCC_INGESTION_INTERNAL_URL", GatewayRoutesTest::backendUrl);
        registry.add("OCC_DISRUPTION_INTERNAL_URL", GatewayRoutesTest::backendUrl);
        registry.add("OCC_PLANNING_INTERNAL_URL", GatewayRoutesTest::backendUrl);
        registry.add("SUPABASE_PROJECT_URL", () -> "https://example.supabase.co");
        registry.add("SUPABASE_JWKS_URL", () -> "https://example.supabase.co/auth/v1/.well-known/jwks.json");
        registry.add("SUPABASE_JWT_AUDIENCE", () -> "authenticated");
        registry.add("SUPABASE_JWT_CLOCK_SKEW", () -> "30s");
        registry.add("SUPABASE_JWT_ALLOWED_ALGORITHMS", () -> "ES256");
    }

    @AfterAll
    static void stopBackend() {
        if (backend != null) backend.stop(0);
    }

    @Test
    void routesCanonicalDisruptionPathWithoutChangingItsResourcePath() {
        ResponseEntity<String> response = get("/api/v1/disruption/bunching-alerts", "CONTROLLER");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("/api/v1/disruption/bunching-alerts");
        assertThat(lastBackendPath.get()).isEqualTo("/api/v1/disruption/bunching-alerts");
    }

    @Test
    void rewritesIngestionNamespaceToItsExistingServicePath() {
        ResponseEntity<String> response = get("/api/v1/ingestion/demo-mode", "ADMIN");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("/api/v1/demo-mode");
        assertThat(lastBackendPath.get()).isEqualTo("/api/v1/demo-mode");
    }

    @Test
    void preservesExistingUnprefixedIngestionApiForTheDemoPage() {
        ResponseEntity<String> response = get("/api/v1/arrivals", "CONTROLLER");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("/api/v1/arrivals");
        assertThat(lastBackendPath.get()).isEqualTo("/api/v1/arrivals");
    }

    @Test
    void captainsReachPacingOnIngestionButNoOtherOperationalApi() {
        ResponseEntity<String> stream = get("/api/v1/pacing-snapshots/stream", "CAPTAIN");
        assertThat(stream.getStatusCode().value()).isEqualTo(200);
        assertThat(lastBackendPath.get()).isEqualTo("/api/v1/pacing-snapshots/stream");
        assertThat(get("/api/v1/pacing-snapshots/latest", "CAPTAIN").getStatusCode().value()).isEqualTo(200);

        lastBackendPath.set(null);
        assertThat(get("/api/v1/arrivals", "CAPTAIN").getStatusCode().value()).isEqualTo(403);
        assertThat(get("/api/v1/pacing-snapshots/latest", "UNASSIGNED").getStatusCode().value()).isEqualTo(403);
        assertThat(get("/api/v1/pacing-snapshots/latest", "DRIVER").getStatusCode().value()).isEqualTo(403);
        assertThat(lastBackendPath.get()).isNull();
    }

    @Test
    void backendDoesNotRouteDemoPages() {
        lastBackendPath.set(null);
        ResponseEntity<String> response = get("/demo.html", "ADMIN");
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(lastBackendPath.get()).isNull();
    }

    private ResponseEntity<String> get(String path, String role) {
        String url = "http://localhost:" + applicationContext.getEnvironment().getProperty("local.server.port") + path;
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(role.toLowerCase());
        return rest.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private static synchronized void startBackend() {
        if (backend != null) return;
        try {
            backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            backend.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                lastBackendPath.set(path);
                byte[] response = path.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(200, response.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(response);
                }
            });
            backend.start();
        } catch (Exception exception) {
            throw new IllegalStateException("Could not start the local gateway route target", exception);
        }
    }

    private static String backendUrl() {
        return "http://127.0.0.1:" + backend.getAddress().getPort();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestDecoderConfig {
        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return token -> {
                Instant now = Instant.now();
                return Jwt.withTokenValue(token)
                        .header("alg", "ES256")
                        .issuer("https://example.supabase.co/auth/v1")
                        .subject("synthetic-user")
                        .audience(List.of("authenticated"))
                        .issuedAt(now.minusSeconds(1))
                        .expiresAt(now.plusSeconds(60))
                        .claim("occ_role", token.toUpperCase())
                        .build();
            };
        }
    }
}
