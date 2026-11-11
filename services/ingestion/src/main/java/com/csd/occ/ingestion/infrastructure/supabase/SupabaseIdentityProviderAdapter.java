package com.csd.occ.ingestion.infrastructure.supabase;

import com.csd.occ.ingestion.domain.IdentityProviderException;
import com.csd.occ.ingestion.domain.IdentityProviderPort;
import com.csd.occ.ingestion.domain.OccRole;
import com.csd.occ.ingestion.infrastructure.config.IdentityProviderProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class SupabaseIdentityProviderAdapter implements IdentityProviderPort {
    private static final int MAX_RESPONSE_BYTES = 8_000_000;
    private final RestClient client;
    private final IdentityProviderProperties settings;
    private final JsonMapper json;
    private final Clock clock;

    public SupabaseIdentityProviderAdapter(@Qualifier("identityProviderRestClient") RestClient client,
            IdentityProviderProperties settings, JsonMapper json, Clock clock) {
        this.client = client; this.settings = settings; this.json = json; this.clock = clock;
    }

    @Override public UserPage listPage(int page, int size) {
        if (page < 1 || size < 1 || size > 1000) throw new IllegalArgumentException("Invalid provider pagination");
        try {
            var response = request(HttpMethod.GET, "/auth/v1/admin/users?page=" + page + "&per_page=" + size, null, false);
            JsonNode root = response.body();
            JsonNode records = root.path("users");
            if (!records.isArray()) throw unavailable();
            var users = new ArrayList<User>();
            for (JsonNode record : records) users.add(user(record));
            // Fetch one more page for an exact multiple. Never stop before all provider pages were fetched.
            return new UserPage(users, response.hasNextPage() || users.size() >= size || root.path("next_page").asInt(0) > page);
        } catch (IdentityProviderException safe) {
            throw safe;
        } catch (Exception invalidResponse) {
            throw unavailable();
        }
    }

    @Override public Optional<User> get(UUID id) {
        try {
            var response = request(HttpMethod.GET, "/auth/v1/admin/users/" + id, null, true);
            if (response == null) return Optional.empty();
            User found = user(response.body());
            if (!found.id().equals(id)) throw unavailable();
            return Optional.of(found);
        } catch (IdentityProviderException safe) {
            throw safe;
        } catch (Exception invalidResponse) {
            throw unavailable();
        }
    }

    @Override public User createUser(CreateUser request) {
        try {
            var payload = Map.of("email", request.email(), "password", request.password(), "email_confirm", true,
                    "user_metadata", Map.of("display_name", request.displayName()),
                    "app_metadata", Map.of("occ_role", request.role().name(),
                            "occ_provisioning_operation_id", request.operationId().toString()));
            return user(request(HttpMethod.POST, "/auth/v1/admin/users", payload, false).body());
        } catch (IdentityProviderException safe) {
            throw safe;
        } catch (Exception invalidResponse) {
            throw unavailable();
        }
    }

    private ProviderResponse request(HttpMethod method, String path, Object payload, boolean missingAllowed) {
        if (!settings.enabled()) throw unavailable();
        try {
            String secret = settings.secretKey();
            if (secret.isBlank()) {
                try (var input = Files.newInputStream(Path.of(settings.secretKeyFile()))) {
                    byte[] bytes = input.readNBytes(4097);
                    if (bytes.length > 4096) throw unavailable();
                    secret = new String(bytes, StandardCharsets.UTF_8).strip();
                }
            }
            if (!secret.startsWith("sb_secret_") || secret.length() <= "sb_secret_".length()
                    || secret.chars().anyMatch(Character::isWhitespace)) throw unavailable();
            var request = client.method(method).uri(path).header("apikey", secret);
            if (payload != null) request.contentType(MediaType.APPLICATION_JSON).body(payload);
            return request.exchange((outgoing, response) -> {
                int status = response.getStatusCode().value();
                if (missingAllowed && status == 404) return null;
                if (!response.getStatusCode().is2xxSuccessful()) {
                    if (method == HttpMethod.POST && status == 409)
                        throw new IdentityProviderException(IdentityProviderException.Reason.DUPLICATE_EMAIL);
                    if (method == HttpMethod.POST && status == 400)
                        throw new IdentityProviderException(IdentityProviderException.Reason.PASSWORD_POLICY);
                    // GoTrue can also return 422: recognize only safe, documented categories, never its message.
                    if (method == HttpMethod.POST && status == 422) {
                        JsonNode error = json.readTree(response.getBody().readNBytes(16_384));
                        // Supabase puts the stable reason in error_code; code is the HTTP status number.
                        String code = error.path("error_code").asText(error.path("code").asText(""));
                        if (code.equals("email_exists") || code.equals("user_already_exists"))
                            throw new IdentityProviderException(IdentityProviderException.Reason.DUPLICATE_EMAIL);
                        if (code.equals("weak_password"))
                            throw new IdentityProviderException(IdentityProviderException.Reason.PASSWORD_POLICY);
                    }
                    throw unavailable();
                }
                byte[] bytes = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length == 0 || bytes.length > MAX_RESPONSE_BYTES) throw unavailable();
                boolean next = response.getHeaders().getOrDefault("Link", java.util.List.of()).stream()
                        .flatMap(value -> java.util.Arrays.stream(value.split(",")))
                        .anyMatch(value -> value.matches("(?s).*;\\s*rel\\s*=\\s*\"?next\"?\\s*.*"));
                return new ProviderResponse(json.readTree(bytes), next);
            });
        } catch (IdentityProviderException safe) {
            throw safe;
        } catch (Exception providerFailure) {
            // Deliberately discard transport/parser causes; they can include keys, request payloads and provider bodies.
            throw unavailable();
        }
    }

    private User user(JsonNode root) {
        UUID id = UUID.fromString(root.path("id").asText(""));
        String email = root.path("email").asText("").trim();
        String name = root.path("user_metadata").path("display_name").asText("").trim();
        if (name.isBlank()) name = root.path("user_metadata").path("full_name").asText("").trim();
        if (name.isBlank()) name = email.isBlank() ? id.toString() : email;
        JsonNode metadata = root.path("app_metadata");
        OccRole role = OccRole.normalized(metadata.path("occ_role").asText(""));
        boolean available = root.path("deleted_at").asText("").isBlank();
        String ban = root.path("banned_until").asText("");
        if (!ban.isBlank() && Instant.parse(ban).isAfter(clock.instant())) available = false;
        String operation = metadata.path("occ_provisioning_operation_id").asText("");
        return new User(id, name, email, role, available, Instant.parse(root.path("created_at").asText("")),
                optionalInstant(root.path("last_sign_in_at").asText("")), operation.isBlank() ? null : UUID.fromString(operation));
    }

    private Instant optionalInstant(String value) { return value.isBlank() ? null : Instant.parse(value); }
    private IdentityProviderException unavailable() { return new IdentityProviderException(IdentityProviderException.Reason.UNAVAILABLE); }
    private record ProviderResponse(JsonNode body, boolean hasNextPage) {
        @Override public String toString() { return "ProviderResponse[REDACTED]"; }
    }
}
