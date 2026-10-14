package com.csd.occ.planning.diagnosis.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.csd.occ.planning.api.error.GlobalExceptionHandler;
import com.csd.occ.planning.diagnosis.domain.DiagnosisQueryService;
import com.csd.occ.planning.diagnosis.domain.DiagnosisRequest;
import com.csd.occ.planning.diagnosis.domain.DiagnosisRequestStatus;
import com.csd.occ.planning.diagnosis.domain.DiagnosisRequestView;
import com.csd.occ.planning.diagnosis.domain.DiagnosisRequestService;
import com.csd.occ.planning.diagnosis.domain.EventType;
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
    @Autowired MockMvc mvc;
    @MockitoBean DiagnosisRequestService requests;
    @MockitoBean DiagnosisQueryService query;

    @Test
    void rejectsInternalCallWithoutThePeerToken() throws Exception {
        UUID eventId = UUID.randomUUID();

        mvc.perform(post("/internal/v1/planning/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"BUS_BUNCHING\",\"eventId\":\"" + eventId
                                + "\",\"producer\":\"disruption\"}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(requests, query);
    }

    @Test
    void acceptsABunchingEventAndForwardsTheStableIdempotencyKey() throws Exception {
        UUID requestId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-09-27T12:00:00Z");
        DiagnosisRequest request = new DiagnosisRequest(requestId, EventType.BUS_BUNCHING,
                "disruption", eventId, DiagnosisRequestService.SCHEMA_VERSION, createdAt);
        when(requests.enqueue(EventType.BUS_BUNCHING, "disruption", eventId, idempotencyKey))
                .thenReturn(request);
        when(query.findRequest(requestId)).thenReturn(new DiagnosisRequestView(
                requestId, null, DiagnosisRequestStatus.PENDING, null, createdAt));

        mvc.perform(post("/internal/v1/planning/requests")
                        .header("X-OCC-Peer-Token", "test-peer-token")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"BUS_BUNCHING\",\"eventId\":\"" + eventId
                                + "\",\"producer\":\"disruption\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.requestId").value(requestId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));

        verify(requests).enqueue(EventType.BUS_BUNCHING, "disruption", eventId, idempotencyKey);
        verify(query).findRequest(requestId);
    }

    @Test
    void rejectsAMissingIdempotencyKeyBeforeQueuingWork() throws Exception {
        UUID eventId = UUID.randomUUID();

        mvc.perform(post("/internal/v1/planning/requests")
                        .header("X-OCC-Peer-Token", "test-peer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"BUS_BUNCHING\",\"eventId\":\"" + eventId
                                + "\",\"producer\":\"disruption\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(requests);
    }

    @Test
    void returnsConflictWhenAnIdempotencyKeyIsReusedForAnotherEvent() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        when(requests.enqueue(eq(EventType.BUS_BUNCHING), eq("disruption"), eq(eventId), eq(idempotencyKey)))
                .thenThrow(new DiagnosisRequestService.IdempotencyConflictException());

        mvc.perform(post("/internal/v1/planning/requests")
                        .header("X-OCC-Peer-Token", "test-peer-token")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"BUS_BUNCHING\",\"eventId\":\"" + eventId
                                + "\",\"producer\":\"disruption\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Idempotency conflict"));
    }
}
