package com.csd.occ.planning.recommendation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import com.csd.occ.planning.infrastructure.security.PlanningSecurityConfig;
import com.csd.occ.planning.infrastructure.security.SupabaseRoleConverter;
import com.csd.occ.planning.recommendation.domain.BusInstruction;
import com.csd.occ.planning.recommendation.domain.BusInstructionAcknowledgementConflictException;
import com.csd.occ.planning.recommendation.domain.BusInstructionNotFoundException;
import com.csd.occ.planning.recommendation.domain.BusInstructionService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest(value = BusInstructionController.class, properties = {
        "occ.security.supabase.project-url=https://example.supabase.co",
        "occ.security.supabase.jwks-url=https://example.supabase.co/auth/v1/.well-known/jwks.json",
        "occ.security.supabase.audience=authenticated",
        "occ.security.supabase.clock-skew=30s",
        "occ.security.supabase.allowed-algorithms=ES256",
        "occ.security.peer.disruption-to-planning-token=test-disruption-peer-token",
        "occ.security.peer.ingestion-to-planning-token=test-ingestion-peer-token"
})
@Import({PlanningSecurityConfig.class, SupabaseRoleConverter.class})
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class BusInstructionControllerTest {
    private static final UUID INSTRUCTION_ID = UUID.fromString("17be89f4-e22d-36a7-ac09-ae447f1b3084");
    private static final UUID RECOMMENDATION_ID = UUID.fromString("0d319a5b-6611-49cc-8462-b5c64f65d122");
    private static final Instant SENT_AT = Instant.parse("2026-10-24T04:00:00Z");

    private final MockMvcTester mvc;

    @MockitoBean
    private BusInstructionService instructions;

    BusInstructionControllerTest(MockMvcTester mvc) {
        this.mvc = mvc;
    }

    @Test
    void captainReadsInstructionsForTheSelectedMockBus() {
        BusInstruction instruction = BusInstruction.sent(INSTRUCTION_ID, RECOMMENDATION_ID,
                "SBS5898U", "Hold for 120 seconds at stop 44241.", SENT_AT);
        when(instructions.findForBus("SBS5898U", 0, 20)).thenReturn(List.of(instruction));
        when(instructions.countForBus("SBS5898U")).thenReturn(1L);

        var response = mvc.get()
                .uri("/api/v1/planning/bus-instructions?busReference=SBS5898U")
                .with(jwt().jwt(token -> token.subject("captain-subject"))
                        .authorities(new SimpleGrantedAuthority("ROLE_CAPTAIN")))
                .exchange();

        assertThat(response).hasStatusOk()
                .bodyJson()
                .extractingPath("items[0].instructionId").isEqualTo(INSTRUCTION_ID.toString());
        assertThat(response).bodyJson().extractingPath("items[0].status").isEqualTo("SENT");
        assertThat(response).bodyJson().extractingPath("items[0].busReference").isEqualTo("SBS5898U");
        assertThat(response).bodyJson().extractingPath("totalItems").isEqualTo(1);
        verify(instructions).findForBus("SBS5898U", 0, 20);
    }

    @Test
    void readEndpointRequiresAuthentication() {
        assertThat(mvc.get()
                .uri("/api/v1/planning/bus-instructions?busReference=SBS5898U")
                .exchange()).hasStatus(401);
    }

    @Test
    void unassignedRoleCannotReadInstructions() {
        assertThat(mvc.get()
                .uri("/api/v1/planning/bus-instructions?busReference=SBS5898U")
                .with(user("unassigned").roles("UNASSIGNED"))
                .exchange()).hasStatus(403);
    }

    @Test
    void missingBusReferenceIsRejected() {
        assertThat(mvc.get()
                .uri("/api/v1/planning/bus-instructions")
                .with(user("captain").roles("CAPTAIN"))
                .exchange()).hasStatus(400);
    }

    @Test
    void captainAcknowledgesInstruction() {
        BusInstruction acknowledged = BusInstruction.sent(INSTRUCTION_ID, RECOMMENDATION_ID,
                "SBS5898U", "Hold for 120 seconds at stop 44241.", SENT_AT)
                .acknowledge("captain-subject", SENT_AT.plusSeconds(5));
        when(instructions.acknowledge(INSTRUCTION_ID, "captain-subject")).thenReturn(acknowledged);

        var response = mvc.post()
                .uri("/api/v1/planning/bus-instructions/{instructionId}/acknowledgement", INSTRUCTION_ID)
                .with(jwt().jwt(token -> token.subject("captain-subject"))
                        .authorities(new SimpleGrantedAuthority("ROLE_CAPTAIN")))
                .exchange();

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("status").isEqualTo("ACKNOWLEDGED");
        assertThat(response).bodyJson().extractingPath("acknowledgedBy").isEqualTo("captain-subject");
        verify(instructions).acknowledge(INSTRUCTION_ID, "captain-subject");
    }

    @Test
    void administratorAcknowledgesInstruction() {
        BusInstruction acknowledged = BusInstruction.sent(INSTRUCTION_ID, RECOMMENDATION_ID,
                "SBS5898U", "Hold for 120 seconds at stop 44241.", SENT_AT)
                .acknowledge("admin-subject", SENT_AT.plusSeconds(5));
        when(instructions.acknowledge(INSTRUCTION_ID, "admin-subject")).thenReturn(acknowledged);

        assertThat(mvc.post()
                .uri("/api/v1/planning/bus-instructions/{instructionId}/acknowledgement", INSTRUCTION_ID)
                .with(jwt().jwt(token -> token.subject("admin-subject"))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .exchange()).hasStatusOk();
    }

    @Test
    void controllerCannotAcknowledgeInstruction() {
        assertThat(mvc.post()
                .uri("/api/v1/planning/bus-instructions/{instructionId}/acknowledgement", INSTRUCTION_ID)
                .with(user("controller").roles("CONTROLLER"))
                .exchange()).hasStatus(403);
    }

    @Test
    void acknowledgementRequiresAuthentication() {
        assertThat(mvc.post()
                .uri("/api/v1/planning/bus-instructions/{instructionId}/acknowledgement", INSTRUCTION_ID)
                .exchange()).hasStatus(401);
    }

    @Test
    void unknownInstructionReturnsNotFoundProblem() {
        when(instructions.acknowledge(INSTRUCTION_ID, "captain-subject"))
                .thenThrow(new BusInstructionNotFoundException(INSTRUCTION_ID));

        var response = mvc.post()
                .uri("/api/v1/planning/bus-instructions/{instructionId}/acknowledgement", INSTRUCTION_ID)
                .with(jwt().jwt(token -> token.subject("captain-subject"))
                        .authorities(new SimpleGrantedAuthority("ROLE_CAPTAIN")))
                .exchange();

        assertThat(response).hasStatus(404);
        assertThat(response).bodyJson().extractingPath("type")
                .isEqualTo("urn:occ:problem:bus-instruction-not-found");
    }

    @Test
    void acknowledgementByDifferentSubjectReturnsConflictProblem() {
        when(instructions.acknowledge(INSTRUCTION_ID, "second-captain"))
                .thenThrow(new BusInstructionAcknowledgementConflictException(INSTRUCTION_ID));

        var response = mvc.post()
                .uri("/api/v1/planning/bus-instructions/{instructionId}/acknowledgement", INSTRUCTION_ID)
                .with(jwt().jwt(token -> token.subject("second-captain"))
                        .authorities(new SimpleGrantedAuthority("ROLE_CAPTAIN")))
                .exchange();

        assertThat(response).hasStatus(409);
        assertThat(response).bodyJson().extractingPath("type")
                .isEqualTo("urn:occ:problem:bus-instruction-acknowledgement-conflict");
    }
}
