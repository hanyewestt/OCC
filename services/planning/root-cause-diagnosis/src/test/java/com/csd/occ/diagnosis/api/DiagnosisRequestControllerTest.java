package com.csd.occ.diagnosis.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.csd.occ.diagnosis.api.error.GlobalExceptionHandler;
import com.csd.occ.diagnosis.domain.DiagnosisQueryService;
import com.csd.occ.diagnosis.domain.DiagnosisRequest;
import com.csd.occ.diagnosis.domain.DiagnosisRequestService;
import com.csd.occ.diagnosis.domain.EventType;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DiagnosisRequestController.class)
@Import(GlobalExceptionHandler.class)
class DiagnosisRequestControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean DiagnosisRequestService requests;
    @MockitoBean DiagnosisQueryService query;

    @Test
    void acceptsABunchingEventAndForwardsTheStableIdempotencyKey() throws Exception {
        UUID requestId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-09-27T12:00:00Z");
        DiagnosisRequest request = new DiagnosisRequest(requestId, EventType.BUS_BUNCHING,
                "bus-bunching-detection", eventId, DiagnosisRequestService.SCHEMA_VERSION, createdAt);
        when(requests.enqueue(EventType.BUS_BUNCHING, "bus-bunching-detection", eventId, idempotencyKey))
                .thenReturn(request);

        mvc.perform(post("/internal/v1/diagnosis-requests")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"BUS_BUNCHING\",\"eventId\":\"" + eventId
                                + "\",\"producer\":\"bus-bunching-detection\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.requestId").value(requestId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));

        verify(requests).enqueue(EventType.BUS_BUNCHING, "bus-bunching-detection", eventId, idempotencyKey);
    }

    @Test
    void rejectsAMissingIdempotencyKeyBeforeQueuingWork() throws Exception {
        UUID eventId = UUID.randomUUID();

        mvc.perform(post("/internal/v1/diagnosis-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"BUS_BUNCHING\",\"eventId\":\"" + eventId
                                + "\",\"producer\":\"bus-bunching-detection\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(requests);
    }

    @Test
    void returnsConflictWhenAnIdempotencyKeyIsReusedForAnotherEvent() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        when(requests.enqueue(eq(EventType.BUS_BUNCHING), eq("bus-bunching-detection"), eq(eventId), eq(idempotencyKey)))
                .thenThrow(new DiagnosisRequestService.IdempotencyConflictException());

        mvc.perform(post("/internal/v1/diagnosis-requests")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"BUS_BUNCHING\",\"eventId\":\"" + eventId
                                + "\",\"producer\":\"bus-bunching-detection\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Idempotency Conflict"));
    }
}
