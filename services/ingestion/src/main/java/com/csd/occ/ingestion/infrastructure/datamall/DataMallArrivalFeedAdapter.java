package com.csd.occ.ingestion.infrastructure.datamall;

import com.csd.occ.ingestion.domain.ArrivalFeedPort;
import com.csd.occ.ingestion.domain.ArrivalFetch;
import com.csd.occ.ingestion.domain.ArrivalPollTarget;
import com.csd.occ.ingestion.infrastructure.config.ArrivalPollingProperties;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;

@Component
public class DataMallArrivalFeedAdapter implements ArrivalFeedPort {
    private final ArrivalPollingProperties properties;
    private final JsonMapper mapper;
    private final HttpClient client;

    public DataMallArrivalFeedAdapter(ArrivalPollingProperties properties, JsonMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        // Never forward the AccountKey to an upstream redirect.
        this.client = HttpClient.newBuilder().connectTimeout(properties.timeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public ArrivalFetch fetch(ArrivalPollTarget target) {
        var request = HttpRequest.newBuilder(URI.create(properties.baseUrl().toString().replaceAll("/$", "")
                        + "/v3/BusArrival?BusStopCode=" + target.stopCode()))
                .timeout(properties.timeout()).header("AccountKey", properties.accountKey()).GET().build();
        var future = client.sendAsync(request, HttpResponse.BodyHandlers.limiting(
                HttpResponse.BodyHandlers.ofByteArray(), properties.maxResponseBytes()));
        try {
            // Bound the complete exchange, including a slowly streamed body.
            var response = future.get(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
            var status = OptionalInt.of(response.statusCode());
            var checksum = Optional.of(sha256(response.body()));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new ArrivalFetch(status, Optional.empty(), checksum, 0, ArrivalFetch.Reason.HTTP_ERROR);
            }
            try {
                String payload = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(response.body())).toString();
                var json = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(payload);
                if (json == null || !json.isObject() || !target.stopCode().equals(json.path("BusStopCode").asString())
                        || !json.path("Services").isArray()) {
                    return new ArrivalFetch(status, Optional.empty(), checksum, 0, ArrivalFetch.Reason.INVALID_RESPONSE);
                }
                return new ArrivalFetch(status, Optional.of(payload), checksum, json.path("Services").size(), ArrivalFetch.Reason.NONE);
            } catch (RuntimeException | java.nio.charset.CharacterCodingException malformed) {
                // Keep a diagnostic checksum, not unvalidated provider text that may reflect credentials.
                return new ArrivalFetch(status, Optional.empty(), checksum, 0, ArrivalFetch.Reason.INVALID_RESPONSE);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failure(ArrivalFetch.Reason.INTERRUPTED);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            // Exception text can contain upstream content or credentials; persist only a stable code.
            return failure(ArrivalFetch.Reason.TRANSPORT_ERROR);
        } finally {
            future.cancel(true);
        }
    }

    @PreDestroy
    public void close() { client.close(); }

    private ArrivalFetch failure(ArrivalFetch.Reason reason) {
        return new ArrivalFetch(OptionalInt.empty(), Optional.empty(), Optional.empty(), 0, reason);
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
