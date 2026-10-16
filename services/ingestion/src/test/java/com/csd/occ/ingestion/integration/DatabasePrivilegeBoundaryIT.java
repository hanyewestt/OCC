package com.csd.occ.ingestion.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@Testcontainers(disabledWithoutDocker = true)
class DatabasePrivilegeBoundaryIT {
    private static final String IMAGE = "postgis/postgis:18-3.6@sha256:7e00e8c3539fdd43f513b98806c8204714dcd09dea683c259e333d7690317119";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("occ")
            .withUsername("occ_admin")
            .withPassword("synthetic-admin")
            .withEnv("OCC_INGESTION_DB_USERNAME", "occ_ingestion")
            .withEnv("OCC_INGESTION_DB_PASSWORD", "synthetic-ingestion")
            .withEnv("OCC_INGESTION_DB_MIGRATOR_USERNAME", "occ_ingestion_migrator")
            .withEnv("OCC_INGESTION_DB_MIGRATOR_PASSWORD", "synthetic-ingestion-migrator")
            .withEnv("OCC_DISRUPTION_DB_USERNAME", "occ_disruption")
            .withEnv("OCC_DISRUPTION_DB_PASSWORD", "synthetic-disruption")
            .withEnv("OCC_DISRUPTION_DB_MIGRATOR_USERNAME", "occ_disruption_migrator")
            .withEnv("OCC_DISRUPTION_DB_MIGRATOR_PASSWORD", "synthetic-disruption-migrator")
            .withEnv("OCC_PLANNING_DB_USERNAME", "occ_planning")
            .withEnv("OCC_PLANNING_DB_PASSWORD", "synthetic-planning")
            .withEnv("OCC_PLANNING_DB_MIGRATOR_USERNAME", "occ_planning_migrator")
            .withEnv("OCC_PLANNING_DB_MIGRATOR_PASSWORD", "synthetic-planning-migrator")
            .withCopyFileToContainer(MountableFile.forHostPath(bootstrapScript()), "/bootstrap.sql");

    @BeforeAll
    static void upgradesLegacyOwnerAndBootstrapsRoles() throws Exception {
        var hbaFile = POSTGRES.execInContainer("psql", "--username=occ_admin", "--dbname=occ", "--tuples-only",
                "--no-align", "--command=SHOW hba_file");
        assertThat(hbaFile.getExitCode()).as(hbaFile.getStderr()).isZero();
        var requireScram = POSTGRES.execInContainer("sed", "-i",
                "1i host all all 127.0.0.1/32 scram-sha-256", hbaFile.getStdout().trim());
        assertThat(requireScram.getExitCode()).as(requireScram.getStderr()).isZero();
        successful("occ_admin", "SELECT pg_reload_conf()");

        successful("occ_admin", """
                CREATE ROLE occ_ingestion LOGIN PASSWORD 'stale-ingestion';
                CREATE ROLE occ_disruption LOGIN PASSWORD 'stale-disruption';
                CREATE ROLE occ_planning LOGIN PASSWORD 'stale-planning';
                CREATE ROLE occ_ingestion_migrator LOGIN PASSWORD 'stale-ingestion-migrator';
                CREATE ROLE occ_disruption_migrator LOGIN PASSWORD 'stale-disruption-migrator';
                CREATE ROLE occ_planning_migrator LOGIN PASSWORD 'stale-planning-migrator';
                CREATE SCHEMA ingestion AUTHORIZATION occ_ingestion;
                CREATE SCHEMA disruption AUTHORIZATION occ_disruption;
                CREATE SCHEMA planning AUTHORIZATION occ_planning;
                SET ROLE occ_ingestion;
                CREATE TABLE ingestion.upgrade_probe (id integer PRIMARY KEY, value text NOT NULL);
                INSERT INTO ingestion.upgrade_probe VALUES (1, 'preserved');
                RESET ROLE;
                CREATE SCHEMA bunching AUTHORIZATION occ_admin;
                CREATE TABLE bunching.source_probe (id integer PRIMARY KEY);
                GRANT USAGE ON SCHEMA bunching TO occ_disruption;
                GRANT SELECT ON TABLE bunching.source_probe TO occ_disruption;
                CREATE SCHEMA diagnosis AUTHORIZATION occ_admin;
                CREATE TABLE diagnosis.source_probe (id integer PRIMARY KEY);
                GRANT USAGE ON SCHEMA diagnosis TO occ_planning;
                GRANT SELECT ON TABLE diagnosis.source_probe TO occ_planning;
                CREATE SCHEMA recommendation AUTHORIZATION occ_admin;
                CREATE TABLE recommendation.source_probe (id integer PRIMARY KEY);
                GRANT USAGE ON SCHEMA recommendation TO occ_planning;
                GRANT SELECT ON TABLE recommendation.source_probe TO occ_planning;
                GRANT USAGE ON SCHEMA recommendation TO PUBLIC;
                GRANT SELECT ON TABLE recommendation.source_probe TO PUBLIC;
                """);
        successfulCommand("psql", "--no-psqlrc", "--username=occ_admin", "--dbname=occ",
                "--set=ON_ERROR_STOP=1", "--file=/bootstrap.sql");
        successfulCommand("psql", "--no-psqlrc", "--username=occ_admin", "--dbname=occ",
                "--set=ON_ERROR_STOP=1", "--file=/bootstrap.sql");
    }

    @Test
    void preservesLegacyRowsAndSeparatesRuntimeFromSchemaOwnership() throws Exception {
        assertThat(scalar("SELECT value FROM ingestion.upgrade_probe WHERE id = 1")).isEqualTo("preserved");
        assertThat(scalar("SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname='ingestion'"))
                .isEqualTo("occ_ingestion_owner");
        assertThat(scalar("SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolbypassrls "
                + "FROM pg_roles WHERE rolname='occ_ingestion'" )).isEqualTo("f");
        assertThat(scalar("SELECT has_schema_privilege('occ_ingestion', 'ingestion', 'USAGE')")).isEqualTo("t");
        assertThat(scalar("SELECT has_schema_privilege('occ_ingestion', 'ingestion', 'CREATE')")).isEqualTo("f");
        assertThat(scalar("SELECT has_table_privilege('occ_ingestion', 'ingestion.upgrade_probe', 'UPDATE')")).isEqualTo("t");
        assertThat(scalar("SELECT has_schema_privilege('occ_ingestion', 'planning', 'USAGE')")).isEqualTo("f");
        assertThat(scalar("SELECT has_table_privilege('occ_disruption_owner', 'bunching.source_probe', 'SELECT')")).isEqualTo("t");
        assertThat(scalar("SELECT has_table_privilege('occ_disruption', 'bunching.source_probe', 'SELECT')")).isEqualTo("f");
        for (String runtime : new String[] {"occ_ingestion", "occ_disruption", "occ_planning"}) {
            assertThat(scalar("SELECT has_schema_privilege('" + runtime + "', 'bunching', 'USAGE')")).isEqualTo("f");
            assertThat(scalar("SELECT has_schema_privilege('" + runtime + "', 'diagnosis', 'USAGE')")).isEqualTo("f");
            assertThat(scalar("SELECT has_schema_privilege('" + runtime + "', 'recommendation', 'USAGE')")).isEqualTo("f");
            assertThat(scalar("SELECT has_table_privilege('" + runtime + "', 'recommendation.source_probe', 'SELECT')")).isEqualTo("f");
        }
        assertThat(scalar("SELECT has_table_privilege('occ_planning_owner', 'diagnosis.source_probe', 'SELECT')")).isEqualTo("t");
        assertThat(scalar("SELECT has_table_privilege('occ_planning_owner', 'recommendation.source_probe', 'SELECT')")).isEqualTo("t");
    }

    @Test
    void migratorCanAssumeOnlyItsOwnerRoleAndRuntimeCannotReadPeersOrRunDdl() throws Exception {
        successful("occ_ingestion_migrator", "SET ROLE occ_ingestion_owner; "
                + "CREATE TABLE ingestion.migration_probe (id integer PRIMARY KEY); "
                + "RESET ROLE;");
        successful("occ_planning_migrator", "SET ROLE occ_planning_owner; "
                + "CREATE TABLE planning.peer_probe (id integer PRIMARY KEY); "
                + "RESET ROLE;");
        successful("occ_ingestion", "INSERT INTO ingestion.migration_probe VALUES (1)");

        assertThat(run("occ_ingestion", "--command", "CREATE TABLE ingestion.runtime_ddl_probe (id integer)").getExitCode())
                .isNotZero();
        assertThat(run("occ_ingestion", "--command", "SELECT * FROM planning.peer_probe").getExitCode()).isNotZero();
        assertThat(run("occ_ingestion_migrator", "--command", "CREATE TABLE ingestion.unassumed_probe (id integer)").getExitCode())
                .isNotZero();
    }

    @Test
    void reconcilesExistingRuntimeAndMigratorPasswordsWithConfiguredValues() throws Exception {
        assertPasswordRotated("occ_ingestion", "stale-ingestion", "synthetic-ingestion");
        assertPasswordRotated("occ_disruption", "stale-disruption", "synthetic-disruption");
        assertPasswordRotated("occ_planning", "stale-planning", "synthetic-planning");
        assertPasswordRotated("occ_ingestion_migrator", "stale-ingestion-migrator", "synthetic-ingestion-migrator");
        assertPasswordRotated("occ_disruption_migrator", "stale-disruption-migrator", "synthetic-disruption-migrator");
        assertPasswordRotated("occ_planning_migrator", "stale-planning-migrator", "synthetic-planning-migrator");
    }

    private static Path bootstrapScript() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("infra/postgres/bootstrap.sql"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Could not locate infra/postgres/bootstrap.sql from the test working directory");
        }
        return current.resolve("infra/postgres/bootstrap.sql");
    }

    private static String scalar(String sql) throws Exception {
        var result = run("occ_admin", "--tuples-only", "--no-align", "--command", sql);
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
        return result.getStdout().trim();
    }

    private static void successful(String username, String sql) throws Exception {
        var result = run(username, "--command", sql);
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    }

    private static void successfulCommand(String executable, String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = executable;
        System.arraycopy(args, 0, command, 1, args.length);
        var result = POSTGRES.execInContainer(command);
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    }

    private static void assertPasswordRotated(String username, String stalePassword, String configuredPassword)
            throws Exception {
        assertThat(connectWithPassword(username, stalePassword).getExitCode()).isNotZero();
        var result = connectWithPassword(username, configuredPassword);
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    }

    private static org.testcontainers.containers.Container.ExecResult connectWithPassword(String username,
                                                                                           String password)
            throws Exception {
        return POSTGRES.execInContainer("env", "PGPASSWORD=" + password, "psql", "--host=127.0.0.1",
                "--username=" + username, "--dbname=occ", "--no-psqlrc", "--command=SELECT 1");
    }

    private static org.testcontainers.containers.Container.ExecResult run(String username, String... args)
            throws Exception {
        String[] command = new String[args.length + 3];
        command[0] = "psql";
        command[1] = "--username=" + username;
        command[2] = "--dbname=occ";
        System.arraycopy(args, 0, command, 3, args.length);
        return POSTGRES.execInContainer(command);
    }
}
