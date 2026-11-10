package com.csd.occ.ingestion.integration;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Applies the actual proposed Supabase SQL to an isolated synthetic PostgreSQL auth schema. */
@Testcontainers(disabledWithoutDocker = true)
class SupabaseAccessTokenHookIT {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("hook_fixture").withUsername("fixture_admin").withPassword("synthetic-fixture");

    @BeforeAll static void installProposedMigration() throws Exception {
        try (var connection = connect(); var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE ROLE supabase_auth_admin NOLOGIN;
                    CREATE ROLE anon NOLOGIN;
                    CREATE ROLE authenticated NOLOGIN;
                    CREATE SCHEMA auth;
                    CREATE TABLE auth.users (
                        id UUID PRIMARY KEY,
                        raw_app_meta_data JSONB NOT NULL,
                        raw_user_meta_data JSONB NOT NULL
                    );
                    """);
            String sql = Files.readString(Path.of("..", "..", "infra", "supabase", "V001__occ_access_token_hook.sql"));
            statement.execute(sql);
            statement.execute(sql); // The reviewed hook installation can be safely re-applied.
        }
    }

    @Test
    void canonicalStoredRoleOverridesSpoofedClaimsAndRetainsOtherClaims() throws Exception {
        UUID id = account("{\"occ_role\":\"CAPTAIN\"}");
        JsonNode event = invoke(id, "{\"iss\":\"synthetic-issuer\",\"aud\":\"authenticated\","
                + "\"occ_role\":\"ADMIN\",\"custom\":{\"retained\":true},"
                + "\"app_metadata\":{\"occ_role\":\"ADMIN\"},\"user_metadata\":{\"occ_role\":\"ADMIN\"}}");
        assertThat(event.path("claims").path("occ_role").asText()).isEqualTo("CAPTAIN");
        assertThat(event.path("claims").path("iss").asText()).isEqualTo("synthetic-issuer");
        assertThat(event.path("claims").path("aud").asText()).isEqualTo("authenticated");
        assertThat(event.path("claims").path("custom").path("retained").asBoolean()).isTrue();
        assertThat(event.path("unrelated_event_field").asText()).isEqualTo("preserved");
    }

    @Test
    void missingUnknownAndUserEditableRolesNeverGrantAccess() throws Exception {
        for (String metadata : new String[]{"{}", "{\"occ_role\":\"SUPER_ADMIN\"}", "{\"occ_role\":null}"}) {
            UUID id = account(metadata);
            assertThat(invoke(id, "{\"occ_role\":\"ADMIN\",\"app_metadata\":{\"occ_role\":\"ADMIN\"},\"user_metadata\":{\"occ_role\":\"ADMIN\"}}")
                    .path("claims").path("occ_role").asText()).isEqualTo("UNASSIGNED");
        }
        assertThat(invoke(UUID.randomUUID(), "{\"occ_role\":\"ADMIN\"}").path("claims").path("occ_role").asText())
                .isEqualTo("UNASSIGNED");
    }

    @Test
    void onlyAuthAdminCanExecuteAndReadTheRequiredIdentityColumns() throws Exception {
        UUID id = account("{\"occ_role\":\"CONTROLLER\"}");
        assertThat(invoke(id, "{}").path("claims").path("occ_role").asText()).isEqualTo("CONTROLLER");
        try (var connection = connect(); var statement = connection.createStatement()) {
            for (String role : new String[]{"anon", "authenticated"}) {
                statement.execute("SET ROLE " + role);
                assertThatThrownBy(() -> statement.executeQuery("SELECT public.occ_custom_access_token_hook('{}'::jsonb)"))
                        .isInstanceOfSatisfying(SQLException.class, failure -> assertThat(failure.getSQLState()).isEqualTo("42501"));
                assertThatThrownBy(() -> statement.executeQuery("SELECT raw_app_meta_data FROM auth.users"))
                        .isInstanceOfSatisfying(SQLException.class, failure -> assertThat(failure.getSQLState()).isEqualTo("42501"));
                statement.execute("RESET ROLE");
            }
            statement.execute("SET ROLE supabase_auth_admin");
            try (var rows = statement.executeQuery("SELECT id,raw_app_meta_data FROM auth.users")) { assertThat(rows.next()).isTrue(); }
            assertThatThrownBy(() -> statement.executeQuery("SELECT raw_user_meta_data FROM auth.users"))
                    .isInstanceOfSatisfying(SQLException.class, failure -> assertThat(failure.getSQLState()).isEqualTo("42501"));
            statement.execute("RESET ROLE");
            try (var result = statement.executeQuery("SELECT prosecdef FROM pg_proc WHERE oid='public.occ_custom_access_token_hook(jsonb)'::regprocedure")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getBoolean(1)).isFalse();
            }
        }
    }

    private UUID account(String appMetadata) throws Exception {
        UUID id = UUID.randomUUID();
        try (var connection = connect(); var statement = connection.prepareStatement(
                "INSERT INTO auth.users (id,raw_app_meta_data,raw_user_meta_data) VALUES (?,?::jsonb,'{\"occ_role\":\"ADMIN\"}'::jsonb)")) {
            statement.setObject(1, id);
            statement.setString(2, appMetadata);
            statement.executeUpdate();
        }
        return id;
    }

    private JsonNode invoke(UUID id, String claims) throws Exception {
        try (var connection = connect(); var role = connection.createStatement()) {
            role.execute("SET ROLE supabase_auth_admin");
            try (var statement = connection.prepareStatement("SELECT public.occ_custom_access_token_hook(?::jsonb)")) {
                statement.setString(1, "{\"user_id\":\"" + id + "\",\"claims\":" + claims + ",\"unrelated_event_field\":\"preserved\"}");
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    return JsonMapper.builder().build().readTree(rows.getString(1));
                }
            }
        }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
