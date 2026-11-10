package com.csd.occ.planning.diagnosis.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.csd.occ.planning.api.error.GlobalExceptionHandler;
import com.csd.occ.planning.diagnosis.api.dto.DiagnosisRequestResponse;
import com.csd.occ.planning.diagnosis.api.dto.TriggerDiagnosisRequest;
import com.csd.occ.planning.diagnosis.domain.DiagnosisQueryService;
import com.csd.occ.planning.diagnosis.domain.DiagnosisFailureException;
import com.csd.occ.planning.diagnosis.domain.DiagnosisRequest;
import com.csd.occ.planning.diagnosis.domain.DiagnosisRequestStatus;
import com.csd.occ.planning.diagnosis.domain.DiagnosisRequestView;
import com.csd.occ.planning.diagnosis.domain.DiagnosisRequestService;
import com.csd.occ.planning.diagnosis.domain.DiagnosisTriggerService;
import com.csd.occ.planning.diagnosis.domain.EarlyBunchingControllerSuggestionService;
import com.csd.occ.planning.diagnosis.domain.EarlyBunchingControllerSuggestion;
import com.csd.occ.planning.recommendation.domain.ControllerBusInstruction;
import java.util.List;
import com.csd.occ.planning.diagnosis.domain.EventType;
import com.csd.occ.planning.diagnosis.domain.ProducerEventSubmission;
import com.csd.occ.planning.diagnosis.domain.TrafficIncidentEventFacts;
import com.csd.occ.planning.advisory.domain.AdvisoryProposalRepository;
import com.csd.occ.planning.advisory.domain.IssuedInstructionRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.csd.occ.planning.infrastructure.security.PlanningSecurityConfig;
import com.csd.occ.planning.infrastructure.security.SupabaseRoleConverter;

@WebMvcTest(value = DiagnosisRequestController.class, properties = {
        "occ.security.supabase.project-url=https://example.supabase.co",
        "occ.security.supabase.jwks-url=https://example.supabase.co/auth/v1/.well-known/jwks.json",
        "occ.security.supabase.audience=authenticated",
        "occ.security.supabase.clock-skew=30s",
        "occ.security.supabase.allowed-algorithms=ES256",
        "occ.security.peer.disruption-to-planning-token=test-peer-token",
        "occ.security.peer.ingestion-to-planning-token=test-ingestion-peer-token"
})
@Import({GlobalExceptionHandler.class, PlanningSecurityConfig.class, SupabaseRoleConverter.class})
class DiagnosisRequestControllerTest {
    private static final String FINGERPRINT = "a".repeat(64);
    @Autowired MockMvc mvc;
    @MockitoBean DiagnosisRequestService requests;
    @MockitoBean DiagnosisQueryService query;
    @MockitoBean AdvisoryProposalRepository proposals;
    @MockitoBean IssuedInstructionRepository instructions;
    @MockitoBean DiagnosisTriggerService triggerService;
    @MockitoBean EarlyBunchingControllerSuggestionService suggestions;

    @Test
    void recordsBusSpecificSuggestionBeforeDiagnosisCompletes() throws Exception {
        UUID requestId = UUID.randomUUID();
        Instant suggestedAt = Instant.parse("2026-11-07T09:00:00Z");
        var instructions = List.of(new ControllerBusInstruction("DEMO-190-01", "Hold at the next stop for 60 seconds."),
                new ControllerBusInstruction("DEMO-190-02", "Continue at the planned pace."),
                new ControllerBusInstruction("DEMO-190-03", "Continue."),
                new ControllerBusInstruction("DEMO-190-04", "Continue."));
        when(suggestions.suggest(eq(requestId), eq(instructions), eq("Restore a safe headway."), eq("controller-subject")))
                .thenReturn(new EarlyBunchingControllerSuggestion(requestId, instructions,
                        "Restore a safe headway.", "controller-subject", suggestedAt));

        mvc.perform(post("/api/v1/planning/diagnoses/{requestId}/controller-suggestion", requestId)
                        .with(jwt().jwt(jwt -> jwt.subject("controller-subject")).authorities(
                                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CONTROLLER")))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"instructions":[
                                  {"busReference":"DEMO-190-01","instruction":"Hold at the next stop for 60 seconds."},
                                  {"busReference":"DEMO-190-02","instruction":"Continue at the planned pace."},
                                  {"busReference":"DEMO-190-03","instruction":"Continue."},
                                  {"busReference":"DEMO-190-04","instruction":"Continue."}
                                ],"controllerRationale":"Restore a safe headway."}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(requestId.toString()))
                .andExpect(jsonPath("$.instructions[3].busReference").value("DEMO-190-04"));
        verify(suggestions).suggest(requestId, instructions, "Restore a safe headway.", "controller-subject");
    }

    @Test
    void listsEarlyControllerSuggestionsForTheAuditLog() throws Exception {
        UUID requestId = UUID.randomUUID();
        when(suggestions.history(0, 50)).thenReturn(List.of(new EarlyBunchingControllerSuggestion(requestId,
                List.of(new ControllerBusInstruction("DEMO-190-01", "Hold for 60 seconds.")),
                "Restore a safe headway.", "controller-subject", Instant.parse("2026-11-07T09:00:00Z"))));
        when(suggestions.countHistory()).thenReturn(1L);

        mvc.perform(get("/api/v1/planning/diagnoses/controller-suggestions")
                        .with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CONTROLLER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].actorSubject").value("controller-subject"))
                .andExpect(jsonPath("$.items[0].instructions[0].busReference").value("DEMO-190-01"));
    }

    @Test
    void exposesTerminalFailureByEventBeforeADiagnosisExists() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        when(query.findRequestByEvent(eventId, EventType.BUS_BUNCHING)).thenReturn(
                new DiagnosisRequestView(requestId, null, DiagnosisRequestStatus.FAILED_TERMINAL,
                        "MODEL_PROVIDER_FAILURE", Instant.parse("2026-10-21T08:09:26Z"), null));

        mvc.perform(get("/api/v1/planning/requests/by-event/{id}", eventId)
                        .with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CONTROLLER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(requestId.toString()))
                .andExpect(jsonPath("$.status").value("FAILED_TERMINAL"))
                .andExpect(jsonPath("$.failureCode").value("MODEL_PROVIDER_FAILURE"))
                .andExpect(jsonPath("$.diagnosisId").isEmpty());
    }

    @Test
    void eventLookupRequiresAnOperationalRole() throws Exception {
        String path = "/api/v1/planning/requests/by-event/" + UUID.randomUUID();
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(jwt().authorities(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CAPTAIN"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(query);
    }

    @Test
    void eventNotYetAcceptedReturnsNotFound() throws Exception {
        UUID eventId = UUID.randomUUID();
        when(query.findRequestByEvent(eventId, EventType.BUS_BUNCHING))
                .thenThrow(new DiagnosisQueryService.DiagnosisNotFoundException());
        mvc.perform(get("/api/v1/planning/requests/by-event/{id}", eventId)
                        .with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsInternalCallWithoutThePeerToken() throws Exception {
        UUID eventId = UUID.randomUUID();

        mvc.perform(post("/internal/v1/planning/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submissionJson(eventId, UUID.randomUUID(), UUID.randomUUID(), observations())))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(requests, query);
    }

    @Test
    void acceptsABunchingEventAndForwardsTheStableIdempotencyKey() throws Exception {
        UUID requestId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID disruptionId = UUID.randomUUID();
        UUID demoRunId = UUID.randomUUID();
        UUID[] observations = observations();
        UUID idempotencyKey = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-09-27T12:00:00Z");
        DiagnosisRequest request = new DiagnosisRequest(requestId, EventType.BUS_BUNCHING,
                "disruption", eventId, DiagnosisRequestService.SCHEMA_VERSION, createdAt);
        ProducerEventSubmission submission = submission(eventId, disruptionId, demoRunId, observations);
        when(requests.enqueue(eq(submission), eq(idempotencyKey), eq(FINGERPRINT)))
                .thenReturn(request);
        when(query.findRequest(requestId)).thenReturn(new DiagnosisRequestView(
                requestId, null, DiagnosisRequestStatus.PENDING, null, createdAt, null));

        mvc.perform(post("/internal/v1/planning/requests")
                        .header("X-OCC-Peer-Token", "test-peer-token")
                        .header("Idempotency-Key", idempotencyKey)
                        .header("X-OCC-Event-Fingerprint", FINGERPRINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submissionJson(eventId, disruptionId, demoRunId, observations)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.requestId").value(requestId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));

        verify(requests).enqueue(submission, idempotencyKey, FINGERPRINT);
        verify(query).findRequest(requestId);
    }

    @Test
    void acceptsTypedTrafficIncidentAndReturnsItsNoActionOutcome() throws Exception {
        UUID requestId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID disruptionId = UUID.randomUUID();
        UUID observationId = UUID.randomUUID();
        UUID pollRunId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        Instant eventAt = Instant.parse("2026-09-28T00:00:00Z");
        var facts = new TrafficIncidentEventFacts(eventId, disruptionId, "OPENED", "OBSERVATION_ONLY", null,
                observationId, pollRunId, "LTA", "d".repeat(64), "Road Obstruction", "Westbound lane blocked",
                new java.math.BigDecimal("1.38"), new java.math.BigDecimal("103.75"), eventAt.minusSeconds(1),
                eventAt, eventAt);
        var submission = new ProducerEventSubmission("planning-event-v1", EventType.TRAFFIC_INCIDENT, "disruption",
                eventId, disruptionId, "OBSERVATION_ONLY", null, eventAt, "traffic-incident-observation-v1",
                java.util.List.of(observationId.toString()), null, facts);
        DiagnosisRequest request = new DiagnosisRequest(requestId, EventType.TRAFFIC_INCIDENT,
                "disruption", eventId, DiagnosisRequestService.SCHEMA_VERSION, eventAt);
        request.completeNoAction("TRAFFIC_INCIDENT_NO_OPERATIONAL_ACTION", eventAt);
        when(requests.enqueue(eq(submission), eq(idempotencyKey), eq(FINGERPRINT))).thenReturn(request);
        when(query.findRequest(requestId)).thenReturn(new DiagnosisRequestView(requestId, null,
                DiagnosisRequestStatus.COMPLETED, null, eventAt, "TRAFFIC_INCIDENT_NO_OPERATIONAL_ACTION"));

        mvc.perform(post("/internal/v1/planning/requests")
                        .header("X-OCC-Peer-Token", "test-peer-token")
                        .header("Idempotency-Key", idempotencyKey)
                        .header("X-OCC-Event-Fingerprint", FINGERPRINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trafficSubmissionJson(eventId, disruptionId, observationId, pollRunId, eventAt)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.diagnosisId").doesNotExist())
                .andExpect(jsonPath("$.policyOutcome").value("TRAFFIC_INCIDENT_NO_OPERATIONAL_ACTION"));

        verify(requests).enqueue(submission, idempotencyKey, FINGERPRINT);
    }

    @Test
    void rejectsAMissingIdempotencyKeyBeforeQueuingWork() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID disruptionId = UUID.randomUUID();
        UUID demoRunId = UUID.randomUUID();

        mvc.perform(post("/internal/v1/planning/requests")
                        .header("X-OCC-Peer-Token", "test-peer-token")
                        .header("X-OCC-Event-Fingerprint", FINGERPRINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submissionJson(eventId, disruptionId, demoRunId, observations())))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(requests);
    }

    @Test
    void returnsConflictWhenAnIdempotencyKeyIsReusedForAnotherEvent() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID disruptionId = UUID.randomUUID();
        UUID demoRunId = UUID.randomUUID();
        UUID[] observations = observations();
        UUID idempotencyKey = UUID.randomUUID();
        ProducerEventSubmission submission = submission(eventId, disruptionId, demoRunId, observations);
        when(requests.enqueue(eq(submission), eq(idempotencyKey), eq(FINGERPRINT)))
                .thenThrow(new DiagnosisRequestService.IdempotencyConflictException());

        mvc.perform(post("/internal/v1/planning/requests")
                        .header("X-OCC-Peer-Token", "test-peer-token")
                        .header("Idempotency-Key", idempotencyKey)
                        .header("X-OCC-Event-Fingerprint", FINGERPRINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submissionJson(eventId, disruptionId, demoRunId, observations)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Idempotency conflict"));
    }

    @Test
    void reportsNonRetryableSnapshotFailuresAsUnprocessableEntity() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID disruptionId = UUID.randomUUID();
        UUID demoRunId = UUID.randomUUID();
        when(requests.enqueue(any(ProducerEventSubmission.class), any(UUID.class), eq(FINGERPRINT)))
                .thenThrow(new DiagnosisFailureException("CONTEXT_HTTP_400", false));

        mvc.perform(post("/internal/v1/planning/requests")
                        .header("X-OCC-Peer-Token", "test-peer-token")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .header("X-OCC-Event-Fingerprint", FINGERPRINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submissionJson(eventId, disruptionId, demoRunId, observations())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Planning input rejected"))
                .andExpect(jsonPath("$.failureCode").value("CONTEXT_HTTP_400"));
    }

    @Test
    void reportsRetryableSnapshotFailuresAsServiceUnavailable() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID disruptionId = UUID.randomUUID();
        UUID demoRunId = UUID.randomUUID();
        when(requests.enqueue(any(ProducerEventSubmission.class), any(UUID.class), eq(FINGERPRINT)))
                .thenThrow(new DiagnosisFailureException("CONTEXT_UNAVAILABLE", true));

        mvc.perform(post("/internal/v1/planning/requests")
                        .header("X-OCC-Peer-Token", "test-peer-token")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .header("X-OCC-Event-Fingerprint", FINGERPRINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submissionJson(eventId, disruptionId, demoRunId, observations())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Planning context unavailable"))
                .andExpect(jsonPath("$.failureCode").value("CONTEXT_UNAVAILABLE"));
    }

    @Test
    void rejectsAMissingEventFingerprintBeforeQueuingWork() throws Exception {
        mvc.perform(post("/internal/v1/planning/requests")
                        .header("X-OCC-Peer-Token", "test-peer-token")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submissionJson(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), observations())))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(requests);
    }

    @Test
    void triggersDiagnosisSuccessfullyWithOperationalRole() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID diagnosisId = UUID.randomUUID();
        when(triggerService.trigger(any(TriggerDiagnosisRequest.class))).thenReturn(
                new DiagnosisRequestResponse(requestId, diagnosisId, DiagnosisRequestStatus.COMPLETED,
                        null, Instant.parse("2026-11-05T07:00:00Z"), null));

        mvc.perform(post("/api/v1/planning/diagnoses/trigger")
                        .with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CONTROLLER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"%s"}
                                """.formatted(eventId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(requestId.toString()))
                .andExpect(jsonPath("$.diagnosisId").value(diagnosisId.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        verify(triggerService).trigger(new TriggerDiagnosisRequest(eventId, null));
    }

    @Test
    void triggerDiagnosisRequiresOperationalRole() throws Exception {
        mvc.perform(post("/api/v1/planning/diagnoses/trigger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"%s"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/v1/planning/diagnoses/trigger")
                        .with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CAPTAIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"%s"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isForbidden());

        verifyNoInteractions(triggerService);
    }

    private UUID[] observations() {
        return new UUID[] {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
    }

    private ProducerEventSubmission submission(UUID eventId, UUID disruptionId, UUID demoRunId, UUID[] observationIds) {
        Instant firstConfirmedAt = Instant.parse("2026-09-27T23:55:00Z");
        var evidenceIds = java.util.stream.Stream.concat(
                java.util.stream.Stream.of("bunching-alert-event:" + eventId),
                java.util.Arrays.stream(observationIds).map(id -> "bus-arrival:" + id)).toList();
        var facts = new com.csd.occ.planning.diagnosis.domain.BunchingEventFacts(eventId, disruptionId, "SIMULATION",
                demoRunId, "190", 1, "44009", observationIds[2], observationIds[3], "CONFIRMED", 600,
                java.util.List.of(360, 390), firstConfirmedAt,
                java.util.List.of(firstConfirmedAt, Instant.parse("2026-09-28T00:00:00Z")),
                java.util.List.of(observationIds), evidenceIds, "bunching-gap-v1");
        return new ProducerEventSubmission("planning-event-v1", EventType.BUS_BUNCHING, "disruption", eventId,
                disruptionId, "SIMULATION", demoRunId, Instant.parse("2026-09-28T00:00:00Z"),
                "bunching-gap-v1", evidenceIds, facts);
    }

    private String submissionJson(UUID eventId, UUID disruptionId, UUID demoRunId, UUID[] observationIds) {
        String eventEvidence = "\"bunching-alert-event:" + eventId + "\","
                + java.util.Arrays.stream(observationIds).map(id -> "\"bus-arrival:" + id + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        String observationValues = java.util.Arrays.stream(observationIds).map(UUID::toString)
                .map(id -> "\"" + id + "\"").collect(java.util.stream.Collectors.joining(","));
        return """
                {"schemaVersion":"planning-event-v1","eventType":"BUS_BUNCHING","producer":"disruption",
                 "eventId":"%s","disruptionId":"%s","classification":"SIMULATION","demoRunId":"%s",
                 "detectedAt":"2026-09-28T00:00:00Z","detectionRuleVersion":"bunching-gap-v1",
                 "evidenceIds":[%s],"eventSnapshot":{"eventId":"%s","bunchingEventId":"%s",
                 "classification":"SIMULATION","demoRunId":"%s","serviceId":"190","directionId":1,
                 "monitoredStopCode":"44009","leadingObservationId":"%s","followingObservationId":"%s",
                 "status":"CONFIRMED","expectedHeadwaySeconds":600,"observedHeadwaySeconds":[360,390],
                 "firstConfirmedAt":"2026-09-27T23:55:00Z",
                 "confirmingObservationTimes":["2026-09-27T23:55:00Z","2026-09-28T00:00:00Z"],
                 "confirmingObservationIds":[%s],"evidenceIds":[%s],"detectionRuleVersion":"bunching-gap-v1"}}
                """.formatted(eventId, disruptionId, demoRunId, eventEvidence, eventId, disruptionId, demoRunId,
                observationIds[2], observationIds[3], observationValues, eventEvidence);
    }

    private String trafficSubmissionJson(UUID eventId, UUID disruptionId, UUID observationId, UUID pollRunId,
            Instant eventAt) {
        return """
                {"schemaVersion":"planning-event-v1","eventType":"TRAFFIC_INCIDENT","producer":"disruption",
                 "eventId":"%s","disruptionId":"%s","classification":"OBSERVATION_ONLY",
                 "detectedAt":"%s","detectionRuleVersion":"traffic-incident-observation-v1",
                 "evidenceIds":["%s"],"trafficSnapshot":{"eventId":"%s","disruptionId":"%s",
                 "action":"OPENED","classification":"OBSERVATION_ONLY","sourceObservationId":"%s",
                 "pollRunId":"%s","source":"LTA","sourceIncidentKey":"%s",
                 "incidentType":"Road Obstruction","message":"Westbound lane blocked",
                 "latitude":1.38,"longitude":103.75,"observedAt":"%s","fetchedAt":"%s","eventAt":"%s"}}
                """.formatted(eventId, disruptionId, eventAt, observationId, eventId, disruptionId,
                observationId, pollRunId, "d".repeat(64), eventAt.minusSeconds(1), eventAt, eventAt);
    }
}
