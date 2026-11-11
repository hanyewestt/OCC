package com.csd.occ.ingestion.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.csd.occ.ingestion.domain.*;
import com.csd.occ.ingestion.infrastructure.config.*;
import com.csd.occ.ingestion.infrastructure.audit.ApplicationAuditBackfillService;
import com.csd.occ.ingestion.infrastructure.datamall.DataMallAdapter;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"occ.datamall.account-key=synthetic-key",
        "occ.ingestion.import-reference-on-startup=false", "occ.ingestion.seed-targets-on-startup=false"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class UserDirectoryIT {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("occ").withUsername("occ_admin").withPassword("synthetic-admin")
            .withInitScript("db/ingestion-test-schema.sql");

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "occ_ingestion");
        registry.add("spring.datasource.password", () -> "synthetic-ingestion");
    }

    @MockitoBean IdentityProviderPort provider;
    @MockitoBean DataMallAdapter dataMall;
    @MockitoBean BusArrivalSchedule schedule;
    @MockitoBean TrafficIncidentSchedule trafficSchedule;
    @MockitoBean DemoReplaySchedule replaySchedule;
    @MockitoBean PacingSchedule pacingSchedule;
    @MockitoBean HazardSchedule hazardSchedule;
    @MockitoBean BunchingControlPort bunching;
    @MockitoBean Clock clock;
    @Autowired DirectoryProjectionService projection;
    @Autowired UserDirectoryService directory;
    @Autowired OccUserRepository users;
    @Autowired ApplicationAuditRetentionService auditRetention;
    @Autowired ApplicationAuditBackfillService auditBackfill;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    private final DirectoryActor admin = new DirectoryActor("verified-admin", OccRole.ADMIN);
    private final Instant first = Instant.parse("2026-11-01T00:00:00Z");

    @BeforeEach void resetDirectory() {
        when(clock.instant()).thenReturn(first);
        jdbc.update("delete from ingestion.application_audit_event");
        jdbc.update("delete from ingestion.occ_user");
        jdbc.update("update ingestion.directory_synchronization set scan_started_at=null, synchronized_at=null where id=1");
    }

    @Test
    void partialProviderFailurePreservesStoredUsersAndCheckpoint() {
        var previous = user("old@example.test", OccRole.CAPTAIN, true);
        projection.publish(first, List.of(previous));
        when(provider.listPage(1, 1000)).thenReturn(new IdentityProviderPort.UserPage(List.of(user("new@example.test", OccRole.CAPTAIN, true)), true));
        when(provider.listPage(2, 1000)).thenThrow(new IdentityProviderException(IdentityProviderException.Reason.UNAVAILABLE));
        assertThatThrownBy(() -> directory.synchronize(admin)).isInstanceOf(IdentityProviderException.class);
        assertThat(users.findById(previous.id())).get().extracting(OccUser::available).isEqualTo(true);
        assertThat(users.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select scan_started_at from ingestion.directory_synchronization where id=1", Instant.class)).isEqualTo(first);
    }

    @Test
    void fullSnapshotMarksMissingUnavailableAndAllowsReusedEmailWithDifferentIdentity() {
        var previous = user("same@example.test", OccRole.CAPTAIN, true);
        var replacement = user("same@example.test", OccRole.CONTROLLER, true);
        projection.publish(first, List.of(previous));
        when(clock.instant()).thenReturn(first.plusSeconds(20));
        projection.publish(first.plusSeconds(10), List.of(replacement));
        assertThat(users.findById(previous.id())).get().extracting(OccUser::available).isEqualTo(false);
        assertThat(users.findById(replacement.id())).get().extracting(OccUser::available).isEqualTo(true);
        assertThat(users.count()).isEqualTo(2);
    }

    @Test
    void olderScanCannotOverwriteNewerSuccessfulPublication() {
        var older = user("old@example.test", OccRole.CAPTAIN, true);
        var newer = user("new@example.test", OccRole.CONTROLLER, true);
        projection.publish(first.plusSeconds(10), List.of(newer));
        Instant checkpoint = projection.publish(first, List.of(older));
        assertThat(users.findById(older.id())).isEmpty();
        assertThat(users.findById(newer.id())).get().extracting(OccUser::available).isEqualTo(true);
        assertThat(checkpoint).isNotNull();
    }

    @Test
    void olderScanDoesNotHideOrOverwriteAUserObservedAfterItStarted() {
        var observed = user("observed@example.test", OccRole.CAPTAIN, true);
        var stale = new IdentityProviderPort.User(observed.id(), "Old name", observed.email(), OccRole.UNASSIGNED,
                false, observed.createdAt(), null, null);
        // A future provisioning confirmation can project one user independently of the full-scan checkpoint.
        users.save(new OccUser(observed, first.plusSeconds(10), first.plusSeconds(10)));
        projection.publish(first, List.of(stale));
        assertThat(users.findById(observed.id())).get().satisfies(row -> {
            assertThat(row.available()).isTrue();
            assertThat(row.role()).isEqualTo(OccRole.CAPTAIN);
        });
        projection.publish(first.plusSeconds(1), List.of());
        assertThat(users.findById(observed.id())).get().extracting(OccUser::available).isEqualTo(true);
    }

    @Test
    void laterStartingOverlappingScanRefreshesTheEarlierPublishedSnapshot() {
        var initial = user("overlap@example.test", OccRole.CAPTAIN, true);
        var later = new IdentityProviderPort.User(initial.id(), "Updated name", initial.email(), OccRole.CONTROLLER,
                true, initial.createdAt(), null, null);
        when(clock.instant()).thenReturn(first.plusSeconds(4));
        projection.publish(first, List.of(initial));
        when(clock.instant()).thenReturn(first.plusSeconds(7));
        projection.publish(first.plusSeconds(2), List.of(later));
        assertThat(users.findById(initial.id())).get().satisfies(row -> {
            assertThat(row.role()).isEqualTo(OccRole.CONTROLLER);
            assertThat(row.displayName()).isEqualTo("Updated name");
            assertThat(row.synchronizedAt()).isEqualTo(first.plusSeconds(7));
        });
    }

    @Test
    void auditRetentionIsIndependentOfOperationalDeletionAndKeepsStableCollectionStart() {
        UUID runId = UUID.randomUUID();
        jdbc.update("insert into ingestion.demo_run (id, scenario_key, scenario_version, seed, status, started_at) values (?, 'retention-test', '1', 1, 'CLOSED', ?)", runId, java.sql.Timestamp.from(first.minusSeconds(1000)));
        Instant cutoff = first.minus(Duration.ofDays(90));
        insertAudit(runId, cutoff.minusNanos(1000), "older");
        insertAudit(runId, cutoff, "at-cutoff");
        insertAudit(runId, cutoff.plusNanos(1000), "newer");
        assertThat(auditRetention.collectionStartedAt()).isEqualTo(first);
        assertThat(auditRetention.purgeExpired(Duration.ofDays(90))).isEqualTo(1);
        assertThat(jdbc.queryForList("select source_key from ingestion.application_audit_event order by source_key", String.class))
                .containsExactly("at-cutoff", "newer");
        jdbc.update("delete from ingestion.demo_run where id=?", runId);
        assertThat(jdbc.queryForObject("select count(*) from ingestion.application_audit_event where run_id=?", Integer.class, runId)).isEqualTo(2);
        jdbc.update("delete from ingestion.application_audit_event");
        assertThat(auditRetention.collectionStartedAt()).isEqualTo(first);
        assertThat(jdbc.queryForObject("select collection_started_at from ingestion.application_audit_metadata where id=1", Instant.class)).isEqualTo(first);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema='ingestion' and table_name='application_audit_event' and column_name='backfilled'", Integer.class)).isEqualTo(1);
    }

    private void insertAudit(UUID runId, Instant occurredAt, String sourceKey) {
        jdbc.update("insert into ingestion.application_audit_event (id, occurred_at, recorded_at, category, event_type, outcome, source_service, actor_type, entity_type, entity_id, run_id, summary, details, source_key) values (?, ?, ?, 'OPERATIONS', 'RETENTION_TEST', 'SUCCESS', 'INGESTION', 'SYSTEM', 'RUN', ?, ?, 'test event', '{}'::jsonb, ?)",
                UUID.randomUUID(), java.sql.Timestamp.from(occurredAt), java.sql.Timestamp.from(first), runId.toString(), runId, sourceKey);
    }

    @Test
    void captainAssignmentComesOnlyFromTheCurrentRunningOrCompletedReplay() throws Exception {
        var captain = user("assigned@example.test", OccRole.CAPTAIN, true);
        projection.publish(first, List.of(captain));
        UUID runId = UUID.randomUUID();
        var run = new DemoRun(runId, first, "multi-route-bunching-v2", 190);
        runRepository.save(run);
        assignmentRepository.save(new DemoVehicleAssignment(new DemoVehicleAssignmentId(runId, "BUS-1"),
                "190", 1, "DEMO-190-01", "SBST", "SD", "captain-code", captain.id()));
        try {
            mvc.perform(get("/api/v1/captains").with(actor("CONTROLLER")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].currentAssignment").isEmpty());
            run.startReplay();
            runRepository.save(run);
            mvc.perform(get("/api/v1/captains").with(actor("ADMIN")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].currentAssignment.busId").value("BUS-1"));
            run.complete(first.plusSeconds(1));
            runRepository.save(run);
            mvc.perform(get("/api/v1/admin/users/{id}", captain.id()).with(actor("ADMIN")))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.currentAssignment.demoRunId").value(runId.toString()));
            run.close(first.plusSeconds(2));
            runRepository.save(run);
            mvc.perform(get("/api/v1/captains").with(actor("CONTROLLER")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].currentAssignment").isEmpty());
        } finally {
            assignmentRepository.deleteById(new DemoVehicleAssignmentId(runId, "BUS-1"));
            runRepository.deleteById(runId);
        }
    }

    @Test
    void pagedQueriesFilterRolesAndLiteralCaseInsensitiveSearch() throws Exception {
        projection.publish(first, List.of(user("alpha@example.test", OccRole.ADMIN, true),
                user("CAPTAIN%one@example.test", OccRole.CAPTAIN, true),
                user("captain-two@example.test", OccRole.CAPTAIN, false),
                user("unassigned@example.test", OccRole.UNASSIGNED, true)));
        mvc.perform(get("/api/v1/admin/users").with(actor("ADMIN")).param("role", "CAPTAIN").param("search", "%ONE").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].email").value("CAPTAIN%one@example.test"))
                .andExpect(jsonPath("$.directorySynchronizedAt").isNotEmpty());
        mvc.perform(get("/api/v1/captains").with(actor("CONTROLLER")).param("available", "true"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].id").exists()).andExpect(jsonPath("$.items[0].role").doesNotExist())
                .andExpect(jsonPath("$.items[0].createdAt").doesNotExist());
    }

    @Test
    void captainDirectoryIncludesUnavailableAccountsUnlessAvailabilityIsFiltered() throws Exception {
        var available = user("available@example.test", OccRole.CAPTAIN, true);
        var unavailable = user("unavailable@example.test", OccRole.CAPTAIN, false);
        projection.publish(first, List.of(available, unavailable, user("controller@example.test", OccRole.CONTROLLER, true)));
        mvc.perform(get("/api/v1/captains").with(actor("CONTROLLER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[1].id").value(unavailable.id().toString()))
                .andExpect(jsonPath("$.items[1].available").doesNotExist());
        mvc.perform(get("/api/v1/captains").with(actor("CONTROLLER")).param("available", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].id").value(available.id().toString()));
    }

    @Test
    void apiRejectsWrongRolesInvalidPagesAndUnknownUsers() throws Exception {
        for (String role : List.of("CONTROLLER", "CAPTAIN", "UNASSIGNED")) {
            mvc.perform(get("/api/v1/admin/users").with(actor(role))).andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/admin/users/synchronizations").with(actor(role))).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/admin/users")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/users").with(actor("ADMIN")).param("size", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/users/{id}", UUID.randomUUID()).with(actor("ADMIN"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/users").with(actor("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.directorySynchronizedAt").isEmpty());
    }

    @Test
    void authenticationAndAuthorizationDenialsAreCapturedWithoutRequestSecrets() throws Exception {
        UUID pathId = UUID.randomUUID();
        String secret = "never-persist-this-query-secret";
        mvc.perform(get("/api/v1/admin/users/{id}", pathId).param("search", secret)
                        .header("X-Untrusted-Marker", "request-header-secret").with(actor("CONTROLLER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/users/{id}", pathId).param("search", secret)
                        .header("X-Untrusted-Marker", "request-header-secret").with(actor("CONTROLLER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/users").param("search", secret)
                        .header("Authorization", "Bearer request-token-secret")
                        .header("X-Untrusted-Marker", "request-header-secret"))
                .andExpect(status().isUnauthorized());

        assertThat(jdbc.queryForList("select event_type from ingestion.application_audit_event where event_type like 'AUTH%DENIED'", String.class))
                .containsExactlyInAnyOrder("AUTHORIZATION_DENIED", "AUTHENTICATION_DENIED");
        String stored = jdbc.queryForObject("select string_agg(concat_ws(' ', source_key, actor_subject, actor_type, entity_id, summary, details::text), ' ') from ingestion.application_audit_event", String.class);
        assertThat(stored).contains("verified-actor", "ADMIN_USERS_DETAIL", "ADMIN_USERS_LIST", "CONTROLLER", "ANONYMOUS")
                .doesNotContain(secret, pathId.toString(), "request-token-secret", "request-header-secret", "Bearer");
        assertThat(jdbc.queryForObject("select actor_subject from ingestion.application_audit_event where actor_type='ANONYMOUS'", String.class)).isNull();
    }

    @Test
    void retainedPollBackfillUsesOriginalOccurrenceTimeAndIsIdempotent() {
        UUID retainedPollId = UUID.randomUUID();
        UUID expiredPollId = UUID.randomUUID();
        Instant retainedCompletedAt = first.minus(Duration.ofDays(10));
        Instant cutoff = first.minus(Duration.ofDays(90));
        insertPollRun(retainedPollId, retainedCompletedAt, "SUCCESS", "BUS", "old diagnostic secret");
        insertPollRun(expiredPollId, cutoff.minusSeconds(1), "FAILED", "BUS", "old diagnostic secret");

        auditBackfill.backfillRetainedRecords();
        auditBackfill.backfillRetainedRecords();

        var rows = jdbc.queryForList("select id, occurred_at, recorded_at, outcome, backfilled, details::text from ingestion.application_audit_event where source_key=?",
                "ingestion-poll:" + retainedPollId);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("occurred_at")).isEqualTo(java.sql.Timestamp.from(retainedCompletedAt));
        assertThat(rows.getFirst().get("recorded_at")).isEqualTo(java.sql.Timestamp.from(first));
        assertThat(rows.getFirst().get("outcome")).isEqualTo("SUCCESS");
        assertThat(rows.getFirst().get("backfilled")).isEqualTo(true);
        assertThat(jdbc.queryForObject("select details ->> 'scope' from ingestion.application_audit_event where source_key=?",
                String.class, "ingestion-poll:" + retainedPollId)).isEqualTo("scope-1");
        assertThat(rows.getFirst().get("details").toString()).doesNotContain("old diagnostic secret");
        assertThat(jdbc.queryForObject("select count(*) from ingestion.application_audit_event where source_key=?", Integer.class,
                "ingestion-poll:" + expiredPollId)).isZero();
    }

    @Test
    void adminAuditAppliesCombinedFiltersBeforePaginationAndUsesStableTieOrdering() throws Exception {
        UUID runId = UUID.randomUUID();
        Instant occurredAt = first.minus(Duration.ofHours(2));
        UUID lowerId = UUID.fromString("00000000-0000-4000-8000-000000000101");
        UUID higherId = UUID.fromString("00000000-0000-4000-8000-000000000102");
        insertAuditEvent(lowerId, occurredAt, "ACCOUNT_ASSIGNMENT", "BUS_ASSIGNMENT_CHANGED", "SUCCESS",
                "admin-actor", "DEMO_RUN", "BUS-1", runId, "{}", "task5-lower");
        insertAuditEvent(higherId, occurredAt, "ACCOUNT_ASSIGNMENT", "BUS_ASSIGNMENT_CHANGED", "SUCCESS",
                "admin-actor", "DEMO_RUN", "BUS-2", runId, "{}", "task5-higher");
        insertAuditEvent(UUID.randomUUID(), first.minus(Duration.ofDays(3)), "OPERATIONS", "FEED_POLL_COMPLETED",
                "FAILURE", "admin-actor", "POLL_RUN", "poll-old", runId, "{}", "task5-old");
        UUID filterRun = UUID.randomUUID();
        insertAuditEvent(UUID.randomUUID(), occurredAt.plusSeconds(1), "OPERATIONS", "FEED_POLL_COMPLETED",
                "FAILURE", "admin-actor", "POLL_RUN", "poll-1", filterRun, "{}", "task5-filter-target");
        insertAuditEvent(UUID.randomUUID(), first.minus(Duration.ofDays(90)), "OPERATIONS", "FEED_POLL_COMPLETED",
                "SUCCESS", "admin-actor", "POLL_RUN", "poll-at-cutoff", filterRun, "{}", "task5-cutoff");
        insertAuditEvent(UUID.randomUUID(), first.minus(Duration.ofDays(91)), "OPERATIONS", "FEED_POLL_COMPLETED",
                "FAILURE", "admin-actor", "POLL_RUN", "poll-expired", filterRun, "{}", "task5-expired");
        auditRetention.collectionStartedAt();
        int eventCount = jdbc.queryForObject("select count(*) from ingestion.application_audit_event", Integer.class);
        int pollCount = jdbc.queryForObject("select count(*) from ingestion.poll_run", Integer.class);

        mvc.perform(get("/api/v1/admin/audit-events").with(actor("ADMIN"))
                        .param("category", "OPERATIONS").param("eventType", "FEED_POLL_COMPLETED")
                        .param("outcome", "FAILURE").param("actorSubject", "admin-actor")
                        .param("runId", filterRun.toString()).param("entityId", "poll-1")
                        .param("from", occurredAt.toString()).param("to", occurredAt.plusSeconds(2).toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.items[0].entityId").value("poll-1"))
                .andExpect(jsonPath("$.items[0].details").doesNotExist())
                .andExpect(jsonPath("$.retentionSeconds").value(Duration.ofDays(90).toSeconds()))
                .andExpect(jsonPath("$.collectionStartedAt").value(first.toString()));

        mvc.perform(get("/api/v1/admin/audit-events").with(actor("ADMIN"))
                        .param("category", "ACCOUNT_ASSIGNMENT").param("page", "0").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[0].id").value(higherId.toString()));
        mvc.perform(get("/api/v1/admin/audit-events").with(actor("ADMIN"))
                        .param("category", "ACCOUNT_ASSIGNMENT").param("page", "1").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(lowerId.toString()));

        // Default is last 24 hours; even explicit wider searches remain within the retained 90-day window.
        mvc.perform(get("/api/v1/admin/audit-events").with(actor("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(3));
        mvc.perform(get("/api/v1/admin/audit-events").with(actor("ADMIN"))
                        .param("from", first.minus(Duration.ofDays(91)).toString()).param("to", first.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(5));
        mvc.perform(get("/api/v1/admin/audit-events").with(actor("ADMIN")).param("size", "101"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/audit-events").with(actor("ADMIN")).param("page", "-1"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/audit-events").with(actor("ADMIN"))
                        .param("from", first.toString()).param("to", first.minusSeconds(1).toString()))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("select count(*) from ingestion.application_audit_event", Integer.class)).isEqualTo(eventCount);
        assertThat(jdbc.queryForObject("select count(*) from ingestion.poll_run", Integer.class)).isEqualTo(pollCount);
        assertThat(jdbc.queryForObject("select outcome from ingestion.poll_run where id=?", String.class,
                jdbc.queryForObject("select id from ingestion.poll_run order by id limit 1", UUID.class))).isNotNull();
    }

    @Test
    void auditFilterIndexesPreserveOccurrenceAndIdOrdering() {
        List<String> indexDefinitions = jdbc.queryForList("""
                select indexdef from pg_indexes
                 where schemaname = 'ingestion' and tablename = 'application_audit_event'
                """, String.class);

        for (String filterColumn : List.of("event_type", "outcome", "actor_subject", "run_id", "entity_id")) {
            assertThat(indexDefinitions).as("audit index for %s", filterColumn)
                    .anySatisfy(indexDefinition -> assertThat(indexDefinition.toLowerCase())
                            .contains("(" + filterColumn + ", occurred_at desc, id desc)"));
        }
    }

    @Test
    void adminAuditDetailIsSanitizedAndEveryAuditRouteIsAdminOnly() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        insertAuditEvent(eventId, first.minusSeconds(60), "AI_PROCESSING", "AI_ATTEMPT_FAILED", "FAILURE",
                "verified-controller", "MODEL_INVOCATION", eventId.toString(), runId,
                "{\"authorization\":\"Bearer private-token\",\"prompt\":\"<script>alert(1)</script>\",\"nested\":{\"sessionToken\":\"private-nested-token\"}}",
                "task5-hostile-detail");
        for (String role : List.of("CONTROLLER", "CAPTAIN", "UNASSIGNED")) {
            mvc.perform(get("/api/v1/admin/audit-events").with(actor(role))).andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/admin/audit-events/{id}", eventId).with(actor(role))).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/admin/audit-events")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/audit-events/{id}", eventId)).andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/admin/audit-events/{id}", eventId).with(actor("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(eventId.toString()))
                .andExpect(jsonPath("$.details.authorization").doesNotExist())
                .andExpect(jsonPath("$.details.nested.sessionToken").doesNotExist())
                .andExpect(jsonPath("$.details.prompt").value("<script>alert(1)</script>"));
        mvc.perform(get("/api/v1/admin/audit-events/{id}", UUID.randomUUID()).with(actor("ADMIN")))
                .andExpect(status().isNotFound());
    }

    private void insertAuditEvent(UUID id, Instant occurredAt, String category, String eventType, String outcome,
            String actorSubject, String entityType, String entityId, UUID runId, String details, String sourceKey) {
        jdbc.update("""
                insert into ingestion.application_audit_event
                    (id, occurred_at, recorded_at, category, event_type, outcome, source_service, actor_subject,
                     actor_type, entity_type, entity_id, run_id, summary, details, source_key)
                values (?, ?, ?, ?, ?, ?, 'INGESTION', ?, 'ADMIN', ?, ?, ?, 'safe summary', ?::jsonb, ?)
                """, id, java.sql.Timestamp.from(occurredAt), java.sql.Timestamp.from(first), category, eventType,
                outcome, actorSubject, entityType, entityId, runId, details, sourceKey);
    }

    private void insertPollRun(UUID id, Instant completedAt, String outcome, String source, String errorMessage) {
        jdbc.update("insert into ingestion.poll_run (id, source, scope_key, idempotency_key, started_at, completed_at, outcome, records_received, error_message) values (?, ?, 'scope-1', ?, ?, ?, ?, 7, ?)",
                id, source, "legacy-" + id, java.sql.Timestamp.from(completedAt.minusSeconds(5)),
                java.sql.Timestamp.from(completedAt), outcome, errorMessage);
    }

    @Test
    void adminSynchronizesEveryProviderPageAndReceivesSafeUnavailableErrors() throws Exception {
        var captain = user("captain@example.test", OccRole.CAPTAIN, true);
        var administrator = user("admin@example.test", OccRole.ADMIN, true);
        when(provider.listPage(1, 1000)).thenReturn(new IdentityProviderPort.UserPage(List.of(captain), true));
        when(provider.listPage(2, 1000)).thenReturn(new IdentityProviderPort.UserPage(List.of(administrator), false));
        mvc.perform(post("/api/v1/admin/users/synchronizations").with(actor("ADMIN")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.directorySynchronizedAt").value(first.toString()));
        assertThat(users.count()).isEqualTo(2);
        when(provider.listPage(1, 1000)).thenThrow(new IdentityProviderException(IdentityProviderException.Reason.UNAVAILABLE));
        mvc.perform(post("/api/v1/admin/users/synchronizations").with(actor("ADMIN")))
                .andExpect(status().isServiceUnavailable()).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value("Identity provider is unavailable"));
        assertThat(users.count()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select scan_started_at from ingestion.directory_synchronization where id=1", Instant.class)).isEqualTo(first);
    }

    @Test
    void runningApplicationServesReadinessAndRejectsUnauthenticatedDirectoryHttp() throws Exception {
        var client = java.net.http.HttpClient.newHttpClient();
        var base = "http://127.0.0.1:" + port;
        var health = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/actuator/health/readiness")).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isEqualTo(200);
        var directory = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/v1/admin/users")).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(directory.statusCode()).isEqualTo(401);
    }

    @Test
    void generatedAssignmentSchemasPermitBothObjectAndNull() throws Exception {
        String contract = mvc.perform(get("/v3/api-docs").with(actor("ADMIN"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var schemas = tools.jackson.databind.json.JsonMapper.builder().build().readTree(contract).path("components").path("schemas");
        var result = schemas.path("ProvisioningOperationResponse").path("properties").path("result");
        assertThat(permitsJsonType(result, "object", schemas)).as("provisioning completed account").isTrue();
        assertThat(permitsJsonType(result, "null", schemas)).as("provisioning pending null result").isTrue();
        for (String name : List.of("CaptainResponse", "DirectoryUserResponse")) {
            var assignment = schemas.path(name).path("properties").path("currentAssignment");
            assertThat(permitsJsonType(assignment, "object", schemas)).as(name + " assigned object").isTrue();
            assertThat(permitsJsonType(assignment, "null", schemas)).as(name + " unassigned null").isTrue();
        }
    }

    /** Check type intersections/unions through refs; complete response examples are also JSON-Schema validated. */
    private boolean permitsJsonType(tools.jackson.databind.JsonNode schema, String type, tools.jackson.databind.JsonNode schemas) {
        if (schema.isMissingNode()) return false;
        var declared = schema.path("type");
        if (declared.isString() && !declared.asText().equals(type)) return false;
        if (declared.isArray() && java.util.stream.StreamSupport.stream(declared.spliterator(), false)
                .noneMatch(value -> value.asText().equals(type))) return false;
        if (schema.has("$ref")) {
            String reference = schema.path("$ref").asText();
            if (!permitsJsonType(schemas.path(reference.substring(reference.lastIndexOf('/') + 1)), type, schemas)) return false;
        }
        if (schema.has("allOf") && java.util.stream.StreamSupport.stream(schema.path("allOf").spliterator(), false)
                .anyMatch(branch -> !permitsJsonType(branch, type, schemas))) return false;
        if (schema.has("anyOf") && java.util.stream.StreamSupport.stream(schema.path("anyOf").spliterator(), false)
                .noneMatch(branch -> permitsJsonType(branch, type, schemas))) return false;
        return !schema.has("oneOf") || java.util.stream.StreamSupport.stream(schema.path("oneOf").spliterator(), false)
                .filter(branch -> permitsJsonType(branch, type, schemas)).count() == 1;
    }

    @Autowired DemoRunRepository runRepository;
    @Autowired DemoVehicleAssignmentRepository assignmentRepository;

    private org.springframework.test.web.servlet.request.RequestPostProcessor actor(String role) {
        return jwt().jwt(jwt -> jwt.subject("verified-actor"))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private IdentityProviderPort.User user(String email, OccRole role, boolean available) {
        return new IdentityProviderPort.User(UUID.randomUUID(), email, email, role, available, first.minusSeconds(1), null, null);
    }
}
