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
    void reviewedContentIsSavedOnlyAsADraftWithSourceProvenance() {
        when(context.hazard(disruptionId)).thenReturn(hazard("ACTIVE", "Use caution.", NOW.plusSeconds(3600)));
        when(notices.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var saved = service.draft(UUID.randomUUID(), disruptionId, "Reviewed title", "Water reported. Use caution.",
                List.of("190"), List.of("Example Stop"), NOW, NOW.plusSeconds(3600), "verified-controller");
        assertThat(saved.status()).isEqualTo("DRAFT");
        assertThat(saved.classification()).isEqualTo("OBSERVATION_ONLY");
        assertThat(saved.demoRunId()).isNull();
        assertThat(saved.title()).isEqualTo("Reviewed title");
        assertThat(saved.message()).isEqualTo("Water reported. Use caution.");
        assertThat(saved.publishedAt()).isNull();
        verifyNoInteractions(drafts);
    }

    @Test
    void clearedSourcesMissingRestrictionsAndExcessiveValidityCannotBeSaved() {
        when(context.hazard(disruptionId)).thenReturn(hazard("CLEARED", null, NOW.plusSeconds(3600)));
        assertThatThrownBy(() -> service.draft(UUID.randomUUID(), disruptionId, "Flood update", "Use caution.",
                List.of(), List.of(), NOW, NOW.plusSeconds(600), "controller"))
                .isInstanceOf(AdvisoryConflictException.class);
        when(context.hazard(disruptionId)).thenReturn(hazard("ACTIVE", "Avoid this road.", NOW.plusSeconds(3600)));
        assertThatThrownBy(() -> service.draft(UUID.randomUUID(), disruptionId, "Flood update", "Use caution.",
                List.of(), List.of(), NOW, NOW.plusSeconds(600), "controller"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("source instruction");
        assertThatThrownBy(() -> service.draft(UUID.randomUUID(), disruptionId, "Flood update", "Avoid this road.",
                List.of(), List.of(), NOW, NOW.plusSeconds(3601), "controller"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("source expiry");
        verify(notices, never()).save(any());
    }

    @Test
    void invalidTimeWindowsAndBlankServiceValuesCannotReachPersistence() {
        for (Instant until : List.of(NOW.minusSeconds(1), NOW, NOW.plusSeconds(60))) {
            assertThatThrownBy(() -> service.draft(UUID.randomUUID(), disruptionId, "Flood update", "Use caution.",
                    List.of("190"), List.of("Example Stop"), NOW.plusSeconds(60), until, "controller"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> service.draft(UUID.randomUUID(), disruptionId, "Flood update", "Use caution.",
                List.of(" "), List.of(), NOW, NOW.plusSeconds(600), "controller"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(context, notices);
    }

    @Test
    void noticeSuggestionUsesCurrentSourceFactsForControllerReview() {
        var hazard = hazard("ACTIVE", null, NOW.plusSeconds(3600));
        var suggestion = new AdvisoryDraftPort.NoticeSuggestion("Flood alert", "Provider report",
                List.of(), List.of(), "SOURCE_FACTS");
        when(context.hazard(disruptionId)).thenReturn(hazard);
        when(drafts.suggestNotice(hazard)).thenReturn(suggestion);

        assertThat(service.suggest(disruptionId)).isSameAs(suggestion);
        verify(drafts).suggestNotice(hazard);
        verifyNoInteractions(notices);
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
    void lifecycleResponsesUseTheSameMicrosecondPrecisionAsPostgres() {
        var notice = draft(UUID.randomUUID());
        when(notices.findForUpdate(notice.id())).thenReturn(Optional.of(notice));
        when(context.hazard(disruptionId)).thenReturn(hazard("ACTIVE", null, NOW.plusSeconds(3600)));
        var precise = new PublicNoticeService(notices, context, drafts,
                Clock.fixed(NOW.plusNanos(123456789), ZoneOffset.UTC));
        assertThat(precise.publish(notice.id(), "controller").publishedAt()).isEqualTo(NOW.plusNanos(123456000));
        assertThat(precise.withdraw(notice.id()).withdrawnAt()).isEqualTo(NOW.plusNanos(123456000));
    }

    @Test
    void correctionReplayAfterExpiryAndWithdrawalDoesNotRevalidateOrRepublish() {
        var previous = draft(UUID.randomUUID());
        var next = draft(UUID.randomUUID());
        previous.publish("first-controller", NOW.minusSeconds(30));
        next.publish("correcting-controller", NOW);
        previous.replace(next.id(), NOW);
        next.withdraw(NOW.plusSeconds(1));
        when(notices.findForUpdate(previous.id())).thenReturn(Optional.of(previous));
        when(notices.findById(next.id())).thenReturn(Optional.of(next));
        var later = new PublicNoticeService(notices, context, drafts, Clock.fixed(NOW.plusSeconds(3600), ZoneOffset.UTC));
        var replay = later.replace(previous.id(), next.id(), disruptionId, next.title(), next.message(), next.services(),
                next.locations(), next.effectiveFrom(), next.effectiveUntil(), "another-controller");
        assertThat(replay).isSameAs(next);
        assertThat(replay.status()).isEqualTo("WITHDRAWN");
        assertThat(replay.publishedAt()).isEqualTo(NOW);
        assertThat(replay.withdrawnAt()).isEqualTo(NOW.plusSeconds(1));
        assertThatThrownBy(() -> later.replace(previous.id(), next.id(), disruptionId, "Different content", next.message(),
                next.services(), next.locations(), next.effectiveFrom(), next.effectiveUntil(), "controller"))
                .isInstanceOf(AdvisoryConflictException.class);
        verifyNoInteractions(context, drafts);
        verify(notices, never()).save(any());
    }

    @Test
    void futureCorrectionsAndExistingPublicationsCannotReplaceCurrentContent() {
        var previous = draft(UUID.randomUUID());
        previous.publish("controller", NOW.minusSeconds(30));
        when(notices.findForUpdate(previous.id())).thenReturn(Optional.of(previous));
        assertThatThrownBy(() -> service.replace(previous.id(), UUID.randomUUID(), disruptionId, "Correction", "Message",
                List.of(), List.of(), NOW.plusSeconds(1), NOW.plusSeconds(60), "controller"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("immediately");
        var next = draft(UUID.randomUUID());
        next.publish("controller", NOW.minusSeconds(20));
        when(notices.findById(next.id())).thenReturn(Optional.of(next));
        assertThatThrownBy(() -> service.replace(previous.id(), next.id(), disruptionId, next.title(), next.message(),
                next.services(), next.locations(), next.effectiveFrom(), next.effectiveUntil(), "controller"))
                .isInstanceOf(AdvisoryConflictException.class).hasMessageContaining("unpublished draft");
        assertThat(previous.status()).isEqualTo("PUBLISHED");
        assertThat(previous.replacementId()).isNull();
        verifyNoInteractions(context);
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
