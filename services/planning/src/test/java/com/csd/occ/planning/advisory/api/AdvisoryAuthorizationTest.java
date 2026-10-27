package com.csd.occ.planning.advisory.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.csd.occ.planning.advisory.domain.*;
import com.csd.occ.planning.api.error.GlobalExceptionHandler;
import com.csd.occ.planning.infrastructure.security.PlanningSecurityConfig;
import com.csd.occ.planning.infrastructure.security.SupabaseRoleConverter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest(value = {AdvisoryController.class, NoticeController.class}, properties = {
        "occ.security.supabase.project-url=https://issuer.test",
        "occ.security.supabase.jwks-url=https://issuer.test/auth/v1/jwks",
        "occ.security.supabase.audience=authenticated", "occ.security.supabase.clock-skew=30s",
        "occ.security.supabase.allowed-algorithms=ES256",
        "occ.security.peer.disruption-to-planning-token=test-disruption-peer-token",
        "occ.security.peer.ingestion-to-planning-token=test-ingestion-peer-token"
})
@Import({GlobalExceptionHandler.class, PlanningSecurityConfig.class, SupabaseRoleConverter.class})
class AdvisoryAuthorizationTest {
    @Autowired MockMvcTester mvc;
    @MockitoBean InstructionService instructions;
    @MockitoBean AdvisoryGroupService groups;
    @MockitoBean PublicNoticeService notices;
    @MockitoBean MeterRegistry metrics;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean Clock clock;
    @MockitoBean AdvisoryProposalRepository proposals;
    @MockitoBean IssuedInstructionRepository issuedInstructions;

    @Test
    void anonymousVisitorsCanReadPublishedNoticesButCannotManageThem() {
        when(notices.current("OBSERVATION_ONLY", null, 0, 20)).thenReturn(Page.empty());
        UUID sourceId = UUID.randomUUID();
        assertThat(mvc.get().uri("/api/v1/planning/public/notices").exchange())
                .hasStatusOk().bodyJson().extractingPath("$.totalItems").isEqualTo(0);
        assertThat(mvc.post().uri("/api/v1/planning/notices").contentType(MediaType.APPLICATION_JSON).content("{}").exchange())
                .hasStatus(401);
        assertThat(mvc.post().uri("/api/v1/planning/notices/{id}/publications", UUID.randomUUID()).exchange()).hasStatus(401);
        assertThat(mvc.post().uri("/api/v1/planning/notices/{id}/suggestions", sourceId).exchange()).hasStatus(401);
        verify(notices, never()).publish(any(), any());
    }

    @Test
    void captainsAndUnassignedRolesCannotUseControllerResources() {
        for (String role : List.of("CAPTAIN", "UNASSIGNED", "DRIVER")) {
            var principal = jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
            UUID sourceId = UUID.randomUUID();
            assertThat(mvc.get().uri("/api/v1/planning/advisories").with(principal).exchange()).hasStatus(403);
            assertThat(mvc.get().uri("/api/v1/planning/instructions").with(principal).exchange()).hasStatus(403);
            assertThat(mvc.get().uri("/api/v1/planning/notices").with(principal).exchange()).hasStatus(403);
            assertThat(mvc.post().uri("/api/v1/planning/notices").with(principal)
                    .contentType(MediaType.APPLICATION_JSON).content("{}").exchange()).hasStatus(403);
            assertThat(mvc.post().uri("/api/v1/planning/notices/{id}/publications", UUID.randomUUID())
                    .with(principal).exchange()).hasStatus(403);
            assertThat(mvc.post().uri("/api/v1/planning/notices/{id}/suggestions", sourceId)
                    .with(principal).exchange()).hasStatus(403);
        }
        verifyNoInteractions(instructions, notices);
    }

    @Test
    void controllerAndAdminCanReviewButCannotActAsCaptains() {
        UUID sourceId = UUID.randomUUID();
        when(instructions.proposals(0, 20, false)).thenReturn(Page.empty());
        when(instructions.proposals(0, 20, true)).thenReturn(Page.empty());
        when(notices.managed(0, 20)).thenReturn(Page.empty());
        when(notices.suggest(sourceId)).thenReturn(new AdvisoryDraftPort.NoticeSuggestion(
                "Flood update", "Reported flooding. Use caution.", List.of("190"), List.of("Example Stop"), "SOURCE_FACTS"));
        for (String role : List.of("CONTROLLER", "ADMIN")) {
            var principal = jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
            assertThat(mvc.get().uri("/api/v1/planning/advisories").with(principal).exchange()).hasStatusOk();
            assertThat(mvc.get().uri("/api/v1/planning/advisories?pendingOnly=true").with(principal).exchange()).hasStatusOk();
            assertThat(mvc.get().uri("/api/v1/planning/notices").with(principal).exchange()).hasStatusOk();
            assertThat(mvc.post().uri("/api/v1/planning/notices/{id}/suggestions", sourceId)
                    .with(principal).exchange()).hasStatusOk().bodyJson()
                    .extractingPath("$.generator").isEqualTo("SOURCE_FACTS");
            assertThat(mvc.get().uri("/api/v1/planning/captain/instructions").with(principal).exchange()).hasStatus(403);
            assertThat(mvc.post().uri("/api/v1/planning/captain/instructions/{id}/acknowledgements", UUID.randomUUID())
                    .with(principal).exchange()).hasStatus(403);
        }
        verify(instructions, times(2)).proposals(0, 20, true);
        verify(instructions, never()).acknowledge(any(), any());
    }

    @Test
    void captainReadsAndAcknowledgesUsingVerifiedSubjectRatherThanRequestContent() {
        String subject = UUID.randomUUID().toString();
        UUID id = UUID.randomUUID();
        var issued = instruction(id, UUID.fromString(subject));
        when(instructions.captain(subject)).thenReturn(List.of(issued));
        when(instructions.acknowledge(id, subject)).thenReturn(issued);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-25T02:00:00Z"));
        when(metrics.counter(anyString(), eq("type"), anyString())).thenReturn(mock(Counter.class));
        var principal = jwt().jwt(token -> token.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_CAPTAIN"));
        var page = mvc.get().uri("/api/v1/planning/captain/instructions").with(principal).exchange();
        assertThat(page).hasStatusOk().bodyJson().extractingPath("$.items[0].id").isEqualTo(id.toString());
        assertThat(page).bodyJson().extractingPath("$.serverTime").isEqualTo("2026-10-25T02:00:00Z");
        assertThat(mvc.post().uri("/api/v1/planning/captain/instructions/{id}/acknowledgements", id)
                .with(principal).header("X-Actor-Subject", "forged-captain")
                .contentType(MediaType.APPLICATION_JSON).content("{\"subject\":\"forged-captain\"}").exchange()).hasStatusOk();
        verify(instructions).acknowledge(id, subject);
        verify(instructions, never()).acknowledge(id, "forged-captain");
    }

    @Test
    void decisionsReceiveOnlyTheVerifiedControllerSubject() {
        UUID id = UUID.randomUUID();
        when(instructions.decide(eq(id), isNull(), isNull(), eq("verified-controller"), isNull()))
                .thenReturn(instruction(id, UUID.randomUUID()));
        when(metrics.counter(anyString(), eq("type"), anyString())).thenReturn(mock(Counter.class));
        assertThat(mvc.post().uri("/api/v1/planning/advisories/{id}/decisions", id)
                .with(jwt().jwt(token -> token.subject("verified-controller"))
                        .authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER")))
                .header("X-Actor-Subject", "forged-controller")
                .contentType(MediaType.APPLICATION_JSON).content("{}").exchange()).hasStatusOk();
        verify(instructions).decide(id, null, null, "verified-controller", null);
    }

    @Test
    void concurrentMovementDecisionReturnsTheWinningInstructionId() {
        UUID proposalId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID winnerId = UUID.randomUUID();
        var proposal = mock(AdvisoryProposal.class);
        var winner = mock(IssuedInstruction.class);
        when(proposals.findById(proposalId)).thenReturn(java.util.Optional.of(proposal));
        when(proposal.demoRunId()).thenReturn(runId);
        when(proposal.busId()).thenReturn("demo-190-b");
        when(issuedInstructions.findByDemoRunIdAndBusIdAndStatusInOrderByIssuedAtAsc(
                runId, "demo-190-b", List.of("ISSUED", "ACKNOWLEDGED"))).thenReturn(List.of(winner));
        when(winner.type()).thenReturn(InstructionType.REDUCE_SPEED);
        when(winner.id()).thenReturn(winnerId);
        when(instructions.decide(eq(proposalId), isNull(), isNull(), anyString(), isNull()))
                .thenThrow(new DataIntegrityViolationException("movement conflict",
                        new org.hibernate.exception.ConstraintViolationException("duplicate movement", null,
                                "uq_issued_instruction_movement")));

        var principal = jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTROLLER"));
        var response = mvc.post().uri("/api/v1/planning/advisories/{id}/decisions", proposalId)
                .with(principal).contentType(MediaType.APPLICATION_JSON).content("{}").exchange();
        assertThat(response).hasStatus(409).bodyJson().extractingPath("$.type")
                .isEqualTo("urn:occ:problem:advisory-conflict");
        assertThat(response).bodyJson().extractingPath("$.detail").isEqualTo(
                        "Active movement instruction " + winnerId + " must be explicitly replaced");
    }

    @Test
    void newPublicAndCaptainAccessDoesNotOpenInternalOrUnknownMutationRoutes() {
        for (String role : List.of("CAPTAIN", "CONTROLLER", "ADMIN", "UNASSIGNED")) {
            var principal = jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
            assertThat(mvc.post().uri("/internal/v1/planning/requests").with(principal)
                    .contentType(MediaType.APPLICATION_JSON).content("{}").exchange()).hasStatus(403);
            assertThat(mvc.get().uri("/internal/v1/planning/recommendations/by-disruption-event/{id}", UUID.randomUUID())
                    .with(principal).exchange()).hasStatus(403);
            assertThat(mvc.post().uri("/api/v1/planning/captain/unknown-operation").with(principal).exchange()).hasStatus(403);
        }
        assertThat(mvc.post().uri("/api/v1/planning/public/notices").contentType(MediaType.APPLICATION_JSON)
                .content("{}").exchange()).hasStatus(401);
        assertThat(mvc.get().uri("/api/v1/planning/captain/instructions").exchange()).hasStatus(401);
    }

    private IssuedInstruction instruction(UUID id, UUID captain) {
        var bus = new AdvisoryContextPort.Bus("demo-190-b", "trip", "SBS5898U", "190", 1, captain, "ASSIGNED", null);
        var candidate = new AdvisoryCandidate(InstructionType.BE_CAUTIOUS,
                new InstructionParameters("44251", "School", 10, 0, null, null), "Reported disruption");
        return new IssuedInstruction(id, UUID.randomUUID(), UUID.randomUUID(), bus, candidate,
                "Disruption near School. Be cautious.", "SIMULATION", "controller", Instant.EPOCH, Duration.ofMinutes(5));
    }
}
