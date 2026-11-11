package com.csd.occ.ingestion.infrastructure.supabase;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.csd.occ.ingestion.domain.IdentityProviderException;
import com.csd.occ.ingestion.domain.IdentityProviderPort;
import com.csd.occ.ingestion.domain.OccRole;
import com.csd.occ.ingestion.infrastructure.config.IdentityProviderProperties;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class SupabaseIdentityProviderAdapterTest {
    @TempDir Path temporary;
    private final UUID id = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private MockRestServiceServer server;

    @Test
    void usesOnlyApiKeyHeaderAndTrustsOnlyServerManagedRole() throws Exception {
        var adapter = adapter();
        server.expect(requestTo("https://provider.test/auth/v1/admin/users?page=1&per_page=2"))
                .andExpect(header("apikey", "sb_secret_synthetic"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("{\"users\":[" + user("", "\"user_metadata\":{\"occ_role\":\"ADMIN\"},") + "]}", MediaType.APPLICATION_JSON));
        var page = adapter.listPage(1, 2);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.users()).singleElement().satisfies(user -> {
            assertThat(user.role()).isEqualTo(OccRole.UNASSIGNED);
            assertThat(user.displayName()).isEqualTo("user@example.test");
            assertThat(user.toString()).doesNotContain("user_metadata", "app_metadata");
        });
        server.verify();
    }

    @Test
    void fullProviderPageRequiresAnotherPage() throws Exception {
        var adapter = adapter();
        server.expect(anything()).andRespond(withSuccess("{\"users\":[" + user("CAPTAIN", "") + "]}", MediaType.APPLICATION_JSON));
        assertThat(adapter.listPage(1, 1).hasMore()).isTrue();
    }

    @Test
    void providerNextLinkCannotBeMistakenForACompleteShortPage() throws Exception {
        var adapter = adapter();
        server.expect(anything()).andRespond(withSuccess("{\"users\":[" + user("CAPTAIN", "") + "]}", MediaType.APPLICATION_JSON)
                .header("Link", "<https://provider.test/auth/v1/admin/users?page=2&per_page=1000>; rel=\"next\""));
        assertThat(adapter.listPage(1, 1000).hasMore()).isTrue();
    }

    @Test
    void createCannotEscalateToAdminOrUnassigned() {
        for (var role : new OccRole[]{OccRole.ADMIN, OccRole.UNASSIGNED}) {
            assertThatThrownBy(() -> new IdentityProviderPort.CreateUser(UUID.randomUUID(), "Name", "user@example.test", role, "synthetic password"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void bannedAndDeletedProviderAccountsAreUnavailable() throws Exception {
        var adapter = adapter();
        for (String state : new String[]{"\"banned_until\":\"2030-01-01T00:00:00Z\",", "\"deleted_at\":\"2026-01-01T00:00:00Z\","}) {
            server.expect(anything()).andRespond(withSuccess(user("CAPTAIN", state), MediaType.APPLICATION_JSON));
        }
        assertThat(adapter.get(id)).get().extracting(IdentityProviderPort.User::available).isEqualTo(false);
        assertThat(adapter.get(id)).get().extracting(IdentityProviderPort.User::available).isEqualTo(false);
    }

    @Test
    void missingProviderUserIsAnEmptyLookup() throws Exception {
        var adapter = adapter();
        server.expect(anything()).andRespond(withStatus(HttpStatus.NOT_FOUND).body("provider-private-body"));
        assertThat(adapter.get(id)).isEmpty();
    }

    @Test
    void providerFailuresNeverExposeBodyHeadersOrCause() throws Exception {
        var adapter = adapter();
        server.expect(anything()).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Private", "private-header").body("provider-private-body sb_secret_synthetic"));
        assertThatThrownBy(() -> adapter.get(id)).isInstanceOf(IdentityProviderException.class)
                .hasMessage("Identity provider is unavailable").hasNoCause()
                .hasToString("com.csd.occ.ingestion.domain.IdentityProviderException: Identity provider is unavailable");
    }

    @Test
    void createRequestWritesCanonicalRoleOperationAndConfirmedEmailWithoutLeakingPassword() throws Exception {
        var adapter = adapter();
        var request = new IdentityProviderPort.CreateUser(UUID.randomUUID(), "Name", "user@example.test", OccRole.CAPTAIN, "synthetic password exact ");
        assertThat(request.toString()).doesNotContain("synthetic password exact");
        server.expect(anything()).andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(headerDoesNotExist("Authorization"))
                .andExpect(content().json("{\"email\":\"user@example.test\",\"password\":\"synthetic password exact \",\"email_confirm\":true,\"user_metadata\":{\"display_name\":\"Name\"},\"app_metadata\":{\"occ_role\":\"CAPTAIN\",\"occ_provisioning_operation_id\":\"" + request.operationId() + "\"}}"))
                .andRespond(withSuccess(user("CAPTAIN", ""), MediaType.APPLICATION_JSON));
        assertThat(adapter.createUser(request).id()).isEqualTo(id);
    }

    @Test
    void duplicateAndPasswordPolicyHaveSafeDomainReasons() throws Exception {
        var adapter = adapter();
        var request = new IdentityProviderPort.CreateUser(UUID.randomUUID(), "Name", "user@example.test", OccRole.CAPTAIN, "synthetic password");
        server.expect(anything()).andRespond(withStatus(HttpStatus.CONFLICT).body("private duplicate email"));
        server.expect(anything()).andRespond(withStatus(HttpStatus.BAD_REQUEST).body("private password"));
        assertThatThrownBy(() -> adapter.createUser(request)).isInstanceOfSatisfying(IdentityProviderException.class,
                failure -> assertThat(failure.reason()).isEqualTo(IdentityProviderException.Reason.DUPLICATE_EMAIL));
        assertThatThrownBy(() -> adapter.createUser(request)).isInstanceOfSatisfying(IdentityProviderException.class,
                failure -> assertThat(failure.reason()).isEqualTo(IdentityProviderException.Reason.PASSWORD_POLICY));
    }

    @Test
    void liveProvider422ErrorCodesMapToSafeDomainReasons() throws Exception {
        var adapter = adapter();
        var request = new IdentityProviderPort.CreateUser(UUID.randomUUID(), "Name", "user@example.test", OccRole.CAPTAIN, "synthetic password");
        for (var error : new String[]{
                "{\"code\":422,\"error_code\":\"email_exists\",\"msg\":\"private duplicate email\"}",
                "{\"code\":422,\"error_code\":\"weak_password\",\"msg\":\"private password\"}"}) {
            server.expect(anything()).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                    .contentType(MediaType.APPLICATION_JSON).body(error));
        }
        assertThatThrownBy(() -> adapter.createUser(request)).isInstanceOfSatisfying(IdentityProviderException.class,
                failure -> assertThat(failure.reason()).isEqualTo(IdentityProviderException.Reason.DUPLICATE_EMAIL));
        assertThatThrownBy(() -> adapter.createUser(request)).isInstanceOfSatisfying(IdentityProviderException.class,
                failure -> assertThat(failure.reason()).isEqualTo(IdentityProviderException.Reason.PASSWORD_POLICY));
    }

    @Test
    void disabledAdapterNeverReadsSecretOrCallsProvider() {
        var settings = new IdentityProviderProperties(false, null, "", Duration.ofSeconds(5), Duration.ofMinutes(5), "");
        var adapter = new SupabaseIdentityProviderAdapter(RestClient.create(), settings, JsonMapper.builder().build(), Clock.systemUTC());
        assertThatThrownBy(() -> adapter.get(id)).isInstanceOf(IdentityProviderException.class).hasNoCause();
    }

    private SupabaseIdentityProviderAdapter adapter() throws Exception {
        var file = temporary.resolve("secret");
        Files.writeString(file, "sb_secret_synthetic\n");
        var settings = new IdentityProviderProperties(true, URI.create("https://provider.test"), file.toString(), Duration.ofSeconds(5), Duration.ofMinutes(5), "");
        var builder = RestClient.builder().baseUrl(settings.projectUrl().toString());
        server = MockRestServiceServer.bindTo(builder).build();
        return new SupabaseIdentityProviderAdapter(builder.build(), settings, JsonMapper.builder().build(),
                Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC));
    }

    private String user(String role, String extra) {
        return "{" + extra + "\"id\":\"" + id + "\",\"email\":\"user@example.test\",\"app_metadata\":{\"occ_role\":\"" + role + "\"},\"created_at\":\"2026-01-01T00:00:00Z\"}";
    }
}
