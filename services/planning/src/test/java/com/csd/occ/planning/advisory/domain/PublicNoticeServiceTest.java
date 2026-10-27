package com.csd.occ.planning.advisory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PublicNoticeServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-25T04:00:00Z");
    private final UUID disruptionId = UUID.randomUUID();
    private final PublicNoticeRepository notices = mock(PublicNoticeRepository.class);
    private final AdvisoryContextPort context = mock(AdvisoryContextPort.class);
    private final AdvisoryDraftPort drafts = mock(AdvisoryDraftPort.class);
    private final PublicNoticeService service = new PublicNoticeService(notices, context, drafts, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void noticeSuggestionUsesCurrentSourceFactsForControllerReview() {
        var hazard = hazard("ACTIVE", null, NOW.plusSeconds(3600));
        var suggestion = new AdvisoryDraftPort.NoticeSuggestion("Flood alert", "Provider report",
                List.of(), List.of(), "SOURCE_FACTS");
        when(context.hazard(disruptionId)).thenReturn(hazard);
        when(drafts.suggestNotice(hazard)).thenReturn(suggestion);

        assertThat(service.suggest(disruptionId)).isSameAs(suggestion);
        verify(drafts).suggestNotice(hazard);
    }

    @Test
    void noticeSuggestionIsRejectedWhenItsSourceIsCleared() {
        when(context.hazard(disruptionId)).thenReturn(hazard("CLEARED", null, NOW.plusSeconds(3600)));

        assertThatThrownBy(() -> service.suggest(disruptionId))
                .isInstanceOf(AdvisoryConflictException.class).hasMessageContaining("no longer current");
        verifyNoInteractions(drafts);
    }

    @Test
    void aDraftCannotPublishAfterTheSourceIntroducesANewRestriction() {
        var notice = draft(UUID.randomUUID());
        when(notices.findForUpdate(notice.id())).thenReturn(Optional.of(notice));
        when(context.hazard(disruptionId)).thenReturn(hazard("ACTIVE", "Avoid the affected area", NOW.plusSeconds(3600)));
        assertThatThrownBy(() -> service.publish(notice.id(), "controller"))
                .isInstanceOf(AdvisoryConflictException.class).hasMessageContaining("instruction changed");
        assertThat(notice.status()).isEqualTo("DRAFT");
    }

    @Test
    void aDraftCannotPublishBeyondAShortenedSourceExpiry() {
        var notice = draft(UUID.randomUUID());
        when(notices.findForUpdate(notice.id())).thenReturn(Optional.of(notice));
        when(context.hazard(disruptionId)).thenReturn(hazard("ACTIVE", null, NOW.plusSeconds(600)));
        assertThatThrownBy(() -> service.publish(notice.id(), "controller"))
                .isInstanceOf(AdvisoryConflictException.class).hasMessageContaining("expiry changed");
        assertThat(notice.status()).isEqualTo("DRAFT");
    }

    @Test
    void aWithdrawnSourceCannotBePublished() {
        var notice = draft(UUID.randomUUID());
        when(notices.findForUpdate(notice.id())).thenReturn(Optional.of(notice));
        when(context.hazard(disruptionId)).thenReturn(hazard("CLEARED", null, NOW.plusSeconds(3600)));
        assertThatThrownBy(() -> service.publish(notice.id(), "controller"))
                .isInstanceOf(AdvisoryConflictException.class).hasMessageContaining("withdrawn");
    }

    @Test
    void repeatedPublicationReturnsTheOriginalPublicationTime() {
        var notice = draft(UUID.randomUUID());
        when(notices.findForUpdate(notice.id())).thenReturn(Optional.of(notice));
        when(context.hazard(disruptionId)).thenReturn(hazard("ACTIVE", null, NOW.plusSeconds(3600)));
        assertThat(service.publish(notice.id(), "controller")).isSameAs(notice);
        assertThat(service.publish(notice.id(), "controller")).isSameAs(notice);
        assertThat(notice.status()).isEqualTo("PUBLISHED");
        assertThat(notice.publishedAt()).isEqualTo(NOW);
        verify(context, times(1)).hazard(disruptionId);
    }

    @Test
    void aRepeatedDraftIdentityWithDifferentContentCannotOverwriteTheOriginal() {
        var notice = draft(UUID.randomUUID());
        when(notices.findById(notice.id())).thenReturn(Optional.of(notice));
        assertThatThrownBy(() -> service.draft(notice.id(), disruptionId, notice.title(), "Changed message",
                notice.services(), notice.locations(), notice.effectiveFrom(), notice.effectiveUntil(), "other-controller"))
                .isInstanceOf(AdvisoryConflictException.class).hasMessageContaining("different content");
        assertThat(notice.message()).isEqualTo("Delays near Example Stop.");
        verify(notices, never()).save(any());
        verifyNoInteractions(context);
    }

    @Test
    void identicalDraftRetryReturnsStoredContentWithoutAnotherInsert() {
        var notice = draft(UUID.randomUUID());
        when(notices.findById(notice.id())).thenReturn(Optional.of(notice));
        var replay = service.draft(notice.id(), disruptionId, notice.title(), notice.message(), notice.services(),
                notice.locations(), notice.effectiveFrom(), notice.effectiveUntil(), "controller");
        assertThat(replay).isSameAs(notice);
        verify(notices, never()).save(any());
    }

    @Test
    void replacementRevalidatesAnExistingDraftBeforeWithdrawingPublishedContent() {
        var previous = draft(UUID.randomUUID());
        previous.publish("controller", NOW.minusSeconds(30));
        var replacement = draft(UUID.randomUUID());
        when(notices.findForUpdate(previous.id())).thenReturn(Optional.of(previous));
        when(notices.findById(replacement.id())).thenReturn(Optional.of(replacement));
        when(context.hazard(disruptionId)).thenReturn(hazard("ACTIVE", "Avoid the affected area", NOW.plusSeconds(3600)));

        assertThatThrownBy(() -> service.replace(previous.id(), replacement.id(), disruptionId,
                replacement.title(), replacement.message(), replacement.services(), replacement.locations(),
                replacement.effectiveFrom(), replacement.effectiveUntil(), "controller"))
                .isInstanceOf(AdvisoryConflictException.class).hasMessageContaining("instruction changed");
        assertThat(previous.status()).isEqualTo("PUBLISHED");
        assertThat(previous.replacementId()).isNull();
        assertThat(replacement.status()).isEqualTo("DRAFT");
    }

    @Test
    void simulationReadsRequireExplicitRunScopeAndCannotMixIntoDefaultReads() {
        assertThatThrownBy(() -> service.current("SIMULATION", null, 0, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.current("OBSERVATION_ONLY", UUID.randomUUID(), 0, 20))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(notices);
    }

    private PublicNotice draft(UUID id) {
        return new PublicNotice(id, hazard("ACTIVE", null, NOW.plusSeconds(3600)), "Service advisory",
                "Delays near Example Stop.", List.of("190"), List.of("Example Stop"), NOW.minusSeconds(60),
                NOW.plusSeconds(1800), "controller", NOW.minusSeconds(60));
    }

    private AdvisoryContextPort.Hazard hazard(String status, String instruction, Instant until) {
        var facts = new HazardEventFacts.Fact("FLOOD", "source-flood", "OBSERVED", NOW.minusSeconds(180),
                null, until, "Flood alert", "Provider report", "Severe", instruction, List.of(),
                null, null, null, null, List.of());
        return new AdvisoryContextPort.Hazard(disruptionId, "FLOOD", status, null, "OBSERVATION_ONLY", null,
                UUID.randomUUID(), NOW, facts, List.of(), List.of());
    }
}
