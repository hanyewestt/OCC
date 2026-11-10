package com.csd.occ.ingestion.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.csd.occ.ingestion.domain.*;
import com.csd.occ.ingestion.infrastructure.config.*;
import com.csd.occ.ingestion.infrastructure.datamall.DataMallAdapter;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"occ.datamall.account-key=synthetic-key", "occ.ingestion.import-reference-on-startup=false", "occ.ingestion.seed-targets-on-startup=false"})
@ActiveProfiles("test") @AutoConfigureMockMvc @Testcontainers(disabledWithoutDocker = true)
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class ProvisioningIT {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgis/postgis:18-3.6").asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("occ").withUsername("occ_admin").withPassword("synthetic-admin").withInitScript("db/ingestion-test-schema.sql");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl); registry.add("spring.datasource.username", () -> "occ_ingestion"); registry.add("spring.datasource.password", () -> "synthetic-ingestion");
    }
    @MockitoBean IdentityProviderPort provider; @MockitoBean DataMallAdapter dataMall;
    @MockitoBean BusArrivalSchedule schedule; @MockitoBean TrafficIncidentSchedule trafficSchedule;
    @MockitoBean DemoReplaySchedule replaySchedule; @MockitoBean PacingSchedule pacingSchedule;
    @MockitoBean HazardSchedule hazardSchedule; @MockitoBean BunchingControlPort bunching; @MockitoBean Clock clock;
    @MockitoBean org.springframework.security.oauth2.jwt.JwtDecoder decoder;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @Autowired DirectoryProjectionService projection;
    @Autowired ProvisioningService service; @Autowired JdbcTemplate jdbc; @Autowired MockMvc mvc;
    final Instant now = Instant.parse("2026-11-11T00:00:00Z");
    final DirectoryActor admin = new DirectoryActor("admin-one", OccRole.ADMIN);
    ProvisioningRequest request(String password) { return new ProvisioningRequest(" Captain ", " USER@Example.Test ", OccRole.CAPTAIN, password); }
    IdentityProviderPort.User created(IdentityProviderPort.CreateUser request) {
        return new IdentityProviderPort.User(UUID.randomUUID(), request.displayName(), request.email(), request.role(), true, now, null, request.operationId());
    }
    @BeforeEach void resetState() {
        when(clock.instant()).thenReturn(now); jdbc.update("delete from ingestion.application_audit_event"); jdbc.update("delete from ingestion.provisioning_operation"); jdbc.update("delete from ingestion.occ_user");
        when(provider.listPage(1,1000)).thenReturn(new IdentityProviderPort.UserPage(List.of(),false));
        doAnswer(call -> created(call.getArgument(0))).when(provider).createUser(any());
    }
    @Test void pendingApiStatusIsVisibleToOtherAdminsAndDeniedToOtherRoles() throws Exception {
        doThrow(new IdentityProviderException(IdentityProviderException.Reason.UNAVAILABLE)).when(provider).createUser(any());
        String body="{\"displayName\":\"Captain\",\"email\":\"user@example.test\",\"role\":\"CAPTAIN\",\"password\":\"original-password\"}";
        var response=mvc.perform(post("/api/v1/admin/users").with(jwt().jwt(token -> token.subject("original-admin")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))).header("Idempotency-Key","pending-key").contentType("application/json").content(body))
            .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.status").value("PENDING")).andReturn();
        String location=response.getResponse().getHeader("Location");
        mvc.perform(get(location).with(jwt().jwt(token -> token.subject("another-admin")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))).andExpect(status().isOk()).andExpect(jsonPath("$.actorSubject").value("original-admin")).andExpect(jsonPath("$.result").doesNotExist());
        mvc.perform(get(location).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER")))).andExpect(status().isForbidden());
        mvc.perform(get(location)).andExpect(status().isUnauthorized());
        for(var role:List.of(OccRole.CONTROLLER,OccRole.CAPTAIN,OccRole.UNASSIGNED)) {
            var actor=new DirectoryActor("actor",role);
            assertThatThrownBy(() -> service.submit(actor,"key",request("original-password"))).isInstanceOf(DirectoryAccessDeniedException.class);
            UUID id=UUID.fromString(location.substring(location.lastIndexOf('/')+1));
            assertThatThrownBy(() -> service.status(actor,id)).isInstanceOf(DirectoryAccessDeniedException.class);
        }
    }
    @Test void safeProviderRejectionReplaysWithoutDispatchOrRepeatedAudit() {
        doThrow(new IdentityProviderException(IdentityProviderException.Reason.PASSWORD_POLICY)).when(provider).createUser(any());
        for(int i=0;i<2;i++) assertThatThrownBy(() -> service.submit(admin,"key",request("original-password"))).isInstanceOf(IdentityProviderException.class).hasMessageNotContaining("original-password");
        verify(provider,times(1)).createUser(any());
        assertThat(jdbc.queryForObject("select count(*) from ingestion.application_audit_event where outcome='FAILURE'",Integer.class)).isEqualTo(1);
    }
    @Test void staleNoMatchCannotOverwriteConfirmedRecovery() throws Exception {
        var dispatched=new java.util.concurrent.atomic.AtomicReference<IdentityProviderPort.CreateUser>();
        doAnswer(call -> { dispatched.set(call.getArgument(0)); throw new IdentityProviderException(IdentityProviderException.Reason.UNAVAILABLE); }).when(provider).createUser(any());
        var pending=service.submit(admin,"key",request("original-password")); var account=created(dispatched.get());
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var scans=new AtomicInteger();
        when(provider.listPage(1,1000)).thenAnswer(call -> {
            if(scans.incrementAndGet()==1) { entered.countDown(); assertThat(release.await(10,TimeUnit.SECONDS)).isTrue(); return new IdentityProviderPort.UserPage(List.of(),false); }
            return new IdentityProviderPort.UserPage(List.of(account),false);
        });
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var old=executor.submit(service::reconcile); assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            try { service.reconcile(); } finally { release.countDown(); }
            old.get(10,TimeUnit.SECONDS);
        }
        assertThat(service.status(admin,pending.id()).status()).isEqualTo(ProvisioningStatus.SUCCEEDED);
    }
    @Test void auditFailureRollsBackProjectionAndCompletionThenRecoveryPublishesOnce() {
        var dispatched=new java.util.concurrent.atomic.AtomicReference<IdentityProviderPort.CreateUser>();
        doAnswer(call -> { dispatched.set(call.getArgument(0)); return created(dispatched.get()); }).when(provider).createUser(any());
        jdbc.execute("alter table ingestion.application_audit_event add constraint ck_test_block_success check(outcome <> 'SUCCESS') not valid");
        ProvisioningService.OperationSnapshot pending;
        try { pending=service.submit(admin,"key",request("original-password")); }
        finally { jdbc.execute("alter table ingestion.application_audit_event drop constraint ck_test_block_success"); }
        assertThat(pending.status()).isEqualTo(ProvisioningStatus.PENDING);
        assertThat(jdbc.queryForObject("select count(*) from ingestion.occ_user",Integer.class)).isZero();
        var account=created(dispatched.get());
        when(provider.listPage(1,1000)).thenReturn(new IdentityProviderPort.UserPage(List.of(account),false));
        service.reconcile(); service.reconcile();
        assertThat(service.status(admin,pending.id()).result().id()).isEqualTo(account.id());
        assertThat(jdbc.queryForObject("select count(*) from ingestion.application_audit_event where outcome='SUCCESS'",Integer.class)).isEqualTo(1);
        projection.publish(now.minusSeconds(1),List.of());
        assertThat(jdbc.queryForObject("select available from ingestion.occ_user where id=?",Boolean.class,account.id())).isTrue();
    }
    @Test void simultaneousFirstSameKeyAdmissionsHaveOneProviderDispatch() throws Exception {
        var start=new CountDownLatch(1); var calls=new AtomicInteger();
        doAnswer(call -> { calls.incrementAndGet(); return created(call.getArgument(0)); }).when(provider).createUser(any());
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var futures=new ArrayList<Future<ProvisioningService.OperationSnapshot>>();
            for(int i=0;i<12;i++) futures.add(executor.submit(() -> { start.await(); return service.submit(admin,"same-first-key",request("original-password")); }));
            start.countDown(); var ids=new HashSet<UUID>(); for(var future:futures) ids.add(future.get(20,TimeUnit.SECONDS).id());
            assertThat(ids).hasSize(1);
        }
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void malformedAttributedSuccessAndDuplicateSnapshotStayUncertain() {
        var malformed=new java.util.concurrent.atomic.AtomicReference<IdentityProviderPort.User>();
        doAnswer(call -> { var valid=created(call.getArgument(0)); var wrong=new IdentityProviderPort.User(valid.id(),valid.displayName(),valid.email(),OccRole.ADMIN,true,now,null,valid.provisioningOperationId()); malformed.set(wrong); return wrong; }).when(provider).createUser(any());
        var pending=service.submit(admin,"key",request("original-password"));
        assertThat(pending.status()).isEqualTo(ProvisioningStatus.PENDING);
        when(provider.listPage(1,1000)).thenReturn(new IdentityProviderPort.UserPage(List.of(malformed.get(),malformed.get()),false)); service.reconcile();
        assertThat(service.status(admin,pending.id()).status()).isEqualTo(ProvisioningStatus.PENDING);
        assertThat(jdbc.queryForObject("select count(*) from ingestion.occ_user",Integer.class)).isZero();
    }
    @Test void runningHttpApplicationCreatesThroughSyntheticProviderAndCredentialWorksImmediately() throws Exception {
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        var captured=new java.util.concurrent.atomic.AtomicReference<tools.jackson.databind.JsonNode>();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        UUID accountId=UUID.randomUUID();
        server.createContext("/auth/v1/admin/users",exchange -> {
            var payload=json.readTree(exchange.getRequestBody().readAllBytes()); captured.set(payload);
            var response=Map.of("id",accountId,"email",payload.path("email").asText(),"created_at",now.toString(),"app_metadata",payload.path("app_metadata"),"user_metadata",payload.path("user_metadata"));
            byte[] bytes=json.writeValueAsBytes(response); exchange.getResponseHeaders().add("Content-Type","application/json"); exchange.sendResponseHeaders(201,bytes.length); try(var out=exchange.getResponseBody()) { out.write(bytes); }
        });
        server.createContext("/auth/v1/token",exchange -> {
            var payload=json.readTree(exchange.getRequestBody().readAllBytes()); var stored=captured.get();
            boolean accepted=stored!=null && stored.path("email_confirm").asBoolean() && stored.path("email").equals(payload.path("email")) && stored.path("password").equals(payload.path("password"));
            byte[] bytes=(accepted ? "{\"authenticated\":true}" : "{\"authenticated\":false}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json"); exchange.sendResponseHeaders(accepted ? 200 : 400,bytes.length); try(var out=exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start(); var secret=java.nio.file.Files.createTempFile("occ-synthetic-provider-",".secret");
        try {
            java.nio.file.Files.writeString(secret,"sb_secret_synthetic_fixture");
            var origin=java.net.URI.create("http://127.0.0.1:"+server.getAddress().getPort());
            var settings=new IdentityProviderProperties(true,origin,secret.toString(),Duration.ofSeconds(5),Duration.ofMinutes(5));
            var adapter=new com.csd.occ.ingestion.infrastructure.supabase.SupabaseIdentityProviderAdapter(org.springframework.web.client.RestClient.builder().baseUrl(origin.toString()).build(),settings,json,clock);
            doAnswer(call -> adapter.createUser(call.getArgument(0))).when(provider).createUser(any());
            when(decoder.decode("synthetic-admin-fixture")).thenReturn(org.springframework.security.oauth2.jwt.Jwt.withTokenValue("synthetic-admin-fixture").header("alg","ES256").subject("http-admin").claim("occ_role","ADMIN").issuedAt(now).expiresAt(now.plusSeconds(300)).build());
            var client=java.net.http.HttpClient.newHttpClient();
            var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+"/api/v1/admin/users")).header("Authorization","Bearer synthetic-admin-fixture").header("Idempotency-Key","http-key").header("Content-Type","application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("displayName","HTTP Captain","email","http@example.test","role","CAPTAIN","password","original-password")))).build();
            var response=client.send(request,java.net.http.HttpResponse.BodyHandlers.ofString()); assertThat(response.statusCode()).isEqualTo(201);
            assertThat(json.readTree(response.body()).path("id").asText()).isEqualTo(accountId.toString());
            assertThat(response.body()).doesNotContain("original-password");
            assertThat(captured.get().path("email_confirm").asBoolean()).isTrue();
            var login=java.net.http.HttpRequest.newBuilder(origin.resolve("/auth/v1/token?grant_type=password")).header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"email\":\"http@example.test\",\"password\":\"original-password\"}")).build();
            assertThat(client.send(login,java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
            var invalid=java.net.http.HttpRequest.newBuilder(origin.resolve("/auth/v1/token?grant_type=password")).header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"email\":\"http@example.test\",\"password\":\"different-password\"}")).build();
            assertThat(client.send(invalid,java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(400);
        } finally { server.stop(0); java.nio.file.Files.deleteIfExists(secret); }
    }
    @Test void noMatchRecordsCredentialRequiredOutcomeExactlyOnce() {
        doThrow(new IdentityProviderException(IdentityProviderException.Reason.UNAVAILABLE)).when(provider).createUser(any());
        var pending=service.submit(admin,"key",request("original-password")); service.reconcile(); service.reconcile();
        assertThat(service.status(admin,pending.id()).status()).isEqualTo(ProvisioningStatus.PASSWORD_REQUIRED);
        assertThat(jdbc.queryForObject("select count(*) from ingestion.application_audit_event where outcome='PASSWORD_REQUIRED'",Integer.class)).isEqualTo(1);
    }
    @Test void apiValidatesAfterTrimmingAndNeverEchoesMalformedCredentials() throws Exception {
        String body="{\"displayName\":\""+" ".repeat(201)+"Captain \",\"email\":\" user@example.test \",\"role\":\"CAPTAIN\",\"password\":\"original-password\"}";
        mvc.perform(post("/api/v1/admin/users").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))).header("Idempotency-Key","key").contentType("application/json").content(body)).andExpect(status().isCreated());
        var result=mvc.perform(post("/api/v1/admin/users").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))).header("Idempotency-Key","bad").contentType("application/json").content("{\"password\":\"sensitive-broken-json\"")).andExpect(status().isBadRequest()).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("sensitive-broken-json");
    }
    @Test void completedReplayReturnsOriginalResultWithoutChangingPasswordAndSafeMismatchConflicts() {
        var first = service.submit(admin,"key",request("original-password"));
        var replay = service.submit(admin,"key",request("different-password"));
        assertThat(first.status()).isEqualTo(ProvisioningStatus.SUCCEEDED); assertThat(replay).isEqualTo(first);
        verify(provider,times(1)).createUser(any());
        assertThatThrownBy(() -> service.submit(admin,"key",new ProvisioningRequest("Other","user@example.test",OccRole.CAPTAIN,"original-password"))).isInstanceOf(ProvisioningConflictException.class);
        assertThat(jdbc.queryForObject("select count(*) from ingestion.application_audit_event",Integer.class)).isEqualTo(1);
        assertThat(service.status(new DirectoryActor("other-admin",OccRole.ADMIN),first.id()).actorSubject()).isEqualTo("admin-one");
    }
    @Test void timeoutRecoversOnlyMatchingOperationAndNeverRetriesCreation() {
        var dispatched = new java.util.concurrent.atomic.AtomicReference<IdentityProviderPort.CreateUser>();
        doAnswer(call -> { dispatched.set(call.getArgument(0)); throw new IdentityProviderException(IdentityProviderException.Reason.UNAVAILABLE); }).when(provider).createUser(any());
        var pending = service.submit(admin,"key",request("original-password")); assertThat(pending.status()).isEqualTo(ProvisioningStatus.PENDING);
        service.submit(admin,"key",request("different-password"));
        assertThat(jdbc.queryForObject("select count(*) from ingestion.application_audit_event where outcome='PENDING'",Integer.class)).isEqualTo(1);
        when(provider.listPage(1,1000)).thenReturn(new IdentityProviderPort.UserPage(List.of(created(dispatched.get())),false));
        service.reconcile();
        assertThat(service.status(admin,pending.id()).status()).isEqualTo(ProvisioningStatus.SUCCEEDED);
        verify(provider,times(1)).createUser(any());
        assertThat(jdbc.queryForObject("select count(*) from ingestion.occ_user",Integer.class)).isEqualTo(1);
    }
    @Test void noMatchRequiresFreshKeyAndOldLateWinnerCannotBeAdoptedByNewAttempt() {
        var dispatched = new ArrayList<IdentityProviderPort.CreateUser>();
        doAnswer(call -> { dispatched.add(call.getArgument(0)); throw new IdentityProviderException(IdentityProviderException.Reason.UNAVAILABLE); }).when(provider).createUser(any());
        var old = service.submit(admin,"old",request("original-password")); service.reconcile();
        assertThat(service.submit(admin,"old",request("fresh-new-password")).status()).isEqualTo(ProvisioningStatus.PASSWORD_REQUIRED);
        var late = created(dispatched.getFirst());
        doThrow(new IdentityProviderException(IdentityProviderException.Reason.DUPLICATE_EMAIL)).when(provider).createUser(any());
        assertThatThrownBy(() -> service.submit(admin,"new",request("fresh-new-password"))).isInstanceOf(ProvisioningConflictException.class);
        when(provider.listPage(1,1000)).thenReturn(new IdentityProviderPort.UserPage(List.of(late),false)); service.reconcile();
        assertThat(service.status(admin,old.id()).result().id()).isEqualTo(late.id());
        assertThat(jdbc.queryForObject("select count(*) from ingestion.application_audit_event where outcome='SUCCESS'",Integer.class)).isEqualTo(1);
    }
    @Test void newWinnerNeverCompletesOldOperation() {
        doThrow(new IdentityProviderException(IdentityProviderException.Reason.UNAVAILABLE)).when(provider).createUser(any());
        var old = service.submit(admin,"old",request("original-password")); service.reconcile();
        doAnswer(call -> created(call.getArgument(0))).when(provider).createUser(any());
        var fresh = service.submit(admin,"new",request("fresh-new-password"));
        var account = fresh.result();
        when(provider.listPage(1,1000)).thenReturn(new IdentityProviderPort.UserPage(List.of(new IdentityProviderPort.User(account.id(),account.displayName(),account.email(),account.role(),true,account.createdAt(),null,fresh.id())),false));
        service.reconcile(); assertThat(service.status(admin,old.id()).status()).isEqualTo(ProvisioningStatus.PASSWORD_REQUIRED);
        assertThat(service.status(admin,fresh.id()).status()).isEqualTo(ProvisioningStatus.SUCCEEDED);
    }
    @Test void partialScanRetainsReservationAndUnrelatedDuplicateIsNeverAdopted() {
        doThrow(new IdentityProviderException(IdentityProviderException.Reason.UNAVAILABLE)).when(provider).createUser(any());
        var pending = service.submit(admin,"old",request("original-password"));
        when(provider.listPage(1,1000)).thenReturn(new IdentityProviderPort.UserPage(List.of(),true)); service.reconcile();
        assertThat(service.status(admin,pending.id()).status()).isEqualTo(ProvisioningStatus.PENDING);
        assertThatThrownBy(() -> service.submit(admin,"new",request("original-password"))).isInstanceOf(ProvisioningConflictException.class);
        verify(provider,times(1)).createUser(any());
    }
    @Test void concurrentSameKeyCreatesOnceAndDifferentKeysCannotReserveSameEmail() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var count = new AtomicInteger();
        doAnswer(call -> { count.incrementAndGet(); entered.countDown(); assertThat(release.await(10,TimeUnit.SECONDS)).isTrue(); return created(call.getArgument(0)); }).when(provider).createUser(any());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> service.submit(admin,"key",request("original-password")));
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            try {
                assertThat(service.submit(admin,"key",request("different-password")).status()).isEqualTo(ProvisioningStatus.PENDING);
                assertThatThrownBy(() -> service.submit(new DirectoryActor("other",OccRole.ADMIN),"different",request("original-password"))).isInstanceOf(ProvisioningConflictException.class);
            } finally { release.countDown(); }
            assertThat(first.get(10,TimeUnit.SECONDS).status()).isEqualTo(ProvisioningStatus.SUCCEEDED);
        }
        assertThat(count.get()).isEqualTo(1);
    }
    @Test void persistenceContainsNoCredentialsOrDerivedValues() {
        service.submit(admin,"key",request("original-password"));
        var columns = jdbc.queryForList("select column_name from information_schema.columns where table_schema='ingestion' and table_name='provisioning_operation'",String.class);
        assertThat(columns).noneMatch(name -> name.contains("password") || name.contains("hash") || name.contains("fingerprint"));
        assertThat(jdbc.queryForObject("select row_to_json(p)::text from ingestion.provisioning_operation p",String.class)).doesNotContain("original-password");
        assertThat(jdbc.queryForObject("select row_to_json(a)::text from ingestion.application_audit_event a",String.class)).doesNotContain("original-password");
    }
    @Test void apiHasSafeCreationStatusAuthorizationAndReplayContracts() throws Exception {
        String body = "{\"displayName\":\"Captain\",\"email\":\"user@example.test\",\"role\":\"CAPTAIN\",\"password\":\"original-password\"}";
        mvc.perform(post("/api/v1/admin/users").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER"))).header("Idempotency-Key","key").contentType("application/json").content(body)).andExpect(status().isForbidden());
        var result = mvc.perform(post("/api/v1/admin/users").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))).header("Idempotency-Key","key").contentType("application/json").content(body)).andExpect(status().isCreated()).andExpect(header().string("Cache-Control","no-store")).andExpect(header().exists("Location")).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("password","original-password");
        mvc.perform(post("/api/v1/admin/users").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))).contentType("application/json").content(body)).andExpect(status().isBadRequest());
    }
}
