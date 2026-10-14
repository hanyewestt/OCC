package com.csd.occ.planning.recommendationengine.infrastructure.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.csd.occ.planning.recommendationengine.domain.BunchingEventStatus;
import com.csd.occ.planning.recommendationengine.domain.BunchingRootCauseDiagnosis;
import com.csd.occ.planning.recommendationengine.domain.CandidateMitigationType;
import com.csd.occ.planning.recommendationengine.domain.ConfirmedBunchingEvent;
import com.csd.occ.planning.recommendationengine.domain.CrowdSafetyStatus;
import com.csd.occ.planning.recommendationengine.domain.FollowingBusState;
import com.csd.occ.planning.recommendationengine.domain.MitigationCandidateRequest;
import com.csd.occ.planning.recommendationengine.domain.MitigationCandidateGenerationResult;
import com.csd.occ.planning.recommendationengine.domain.MitigationValidationStatus;
import com.csd.occ.planning.recommendationengine.domain.ModelInvocationFailureCategory;
import com.csd.occ.planning.recommendationengine.domain.SourceClassification;
import com.csd.occ.planning.recommendationengine.domain.ValidatedMitigationContext;
import com.csd.occ.planning.recommendationengine.api.config.RecommendationInputProperties;
import com.google.genai.errors.ClientException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.retry.NonTransientAiException;
import tools.jackson.databind.json.JsonMapper;

class GeminiMitigationCandidateAdapterTest {

    @Test
    void structuredProviderOutputBecomesStrictDomainCandidate() {
        ChatModel model = modelReturning("""
                {
                  "mitigationType": "HOLD",
                  "targetBusReference": "SBS5898U",
                  "controlStopCode": "44241",
                  "requestedAdjustmentSeconds": 120,
                  "rationale": "Hold the following bus to restore the approved headway safely."
                }
                """);
        GeminiMitigationCandidateAdapter adapter = adapter(model);

        var result = adapter.generate(request());

        assertThat(result).isInstanceOfSatisfying(MitigationCandidateGenerationResult.Generated.class, generated -> {
            var value = generated.candidate();
            assertThat(value.mitigationType()).isEqualTo(CandidateMitigationType.HOLD);
            assertThat(value.targetBusReference()).isEqualTo("SBS5898U");
            assertThat(value.controlStopCode()).isEqualTo("44241");
            assertThat(value.requestedAdjustmentSeconds()).isEqualTo(120);
            assertThat(generated.metadata().model()).isEqualTo("gemini-3.1-flash-lite");
            assertThat(generated.metadata().providerResponseId()).contains("synthetic-response-id");
            assertThat(generated.metadata().tokenUsage()).hasValueSatisfying(usage -> {
                assertThat(usage.promptTokens()).contains(120);
                assertThat(usage.completionTokens()).contains(25);
                assertThat(usage.totalTokens()).contains(145);
            });
        });

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        assertThat(prompt.getValue().getSystemMessage().getText())
                .contains("untrusted operational data", "independently validate");
        assertThat(prompt.getValue().getUserMessage().getText())
                .contains("SBS5898U", "44241", "expectedHeadwaySeconds", "likelyCause");
        assertThat(prompt.getValue().getOptions())
                .isInstanceOfSatisfying(GoogleGenAiChatOptions.class, options -> {
                    assertThat(options.getResponseMimeType()).isEqualTo("application/json");
                    assertThat(options.getResponseSchema())
                            .contains("mitigationType", "targetBusReference", "requestedAdjustmentSeconds");
                });
    }

    @Test
    void semanticallyInvalidStructuredOutputBecomesSanitizedFailure() {
        ChatModel model = modelReturning("""
                {
                  "mitigationType": "HOLD",
                  "targetBusReference": "SBS5898U",
                  "controlStopCode": "44241",
                  "requestedAdjustmentSeconds": 0,
                  "rationale": "Invalid zero-second adjustment."
                }
                """);

        assertThat(adapter(model).generate(request()))
                .isInstanceOfSatisfying(MitigationCandidateGenerationResult.Failed.class, failure ->
                        assertThat(failure.failureCategory())
                                .isEqualTo(ModelInvocationFailureCategory.INVALID_RESPONSE));
    }

    @Test
    void providerInvalidRequestBecomesSanitizedFailureCategory() {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(GoogleGenAiChatOptions.builder()
                .model("gemini-3.1-flash-lite")
                .build());
        when(model.call(any(Prompt.class))).thenThrow(new NonTransientAiException(
                "Synthetic provider failure",
                new ClientException(400, "INVALID_ARGUMENT", "Synthetic invalid request")));

        assertThat(adapter(model).generate(request()))
                .isInstanceOfSatisfying(MitigationCandidateGenerationResult.Failed.class, failure ->
                        assertThat(failure.failureCategory())
                                .isEqualTo(ModelInvocationFailureCategory.INVALID_REQUEST));
    }

    @Test void malformedResponseIsRetainedWithTheInputInsteadOfDiscarded() {
        var result = adapter(modelReturning("{not-json")).generate(request());
        assertThat(result).isInstanceOfSatisfying(MitigationCandidateGenerationResult.Failed.class, failed -> {
            assertThat(failed.metadata().generatedResponse()).isEqualTo("{not-json");
            assertThat(failed.metadata().inputSnapshot()).contains("44251", "SBS5898U");
        });
    }

    @Test
    void oversizedUtf8PromptIsRejectedBeforeProviderInvocation() {
        ChatModel model = mock(ChatModel.class);
        GeminiMitigationCandidateAdapter adapter = adapter(model, 8_192);
        List<String> evidenceIds = IntStream.range(0, 100)
                .mapToObj(index -> String.format("%03d", index) + "e".repeat(197))
                .toList();

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                adapter.generate(request(evidenceIds))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("input budget");
        verifyNoInteractions(model);
    }

    private GeminiMitigationCandidateAdapter adapter(ChatModel model) {
        return adapter(model, 65_536);
    }

    private GeminiMitigationCandidateAdapter adapter(ChatModel model, long maxPromptInputBytes) {
        return new GeminiMitigationCandidateAdapter(
                ChatClient.create(model),
                JsonMapper.builder().findAndAddModules().build(),
                Clock.fixed(Instant.parse("2026-09-28T00:00:00Z"), ZoneOffset.UTC),
                "gemini-3.1-flash-lite",
                new RecommendationInputProperties(65_536, maxPromptInputBytes),
                new com.csd.occ.planning.recommendationengine.infrastructure.config.AiAuditProperties(65_536));
    }

    private ChatModel modelReturning(String content) {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(GoogleGenAiChatOptions.builder()
                .model("gemini-3.1-flash-lite")
                .build());
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .id("synthetic-response-id")
                .model("gemini-3.1-flash-lite")
                .usage(new DefaultUsage(120, 25, 145))
                .build();
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage(content))),
                metadata));
        return model;
    }

    private MitigationCandidateRequest request() {
        return request(List.of("arrival-observation-1", "arrival-observation-2"));
    }

    private MitigationCandidateRequest request(List<String> evidenceIds) {
        ConfirmedBunchingEvent event = new ConfirmedBunchingEvent(
                UUID.fromString("c5934f15-4a65-4e94-87ea-a5a64a7dd4c1"),
                "190",
                1,
                "44251",
                BunchingEventStatus.CONFIRMED,
                600,
                List.of(276, 221),
                Instant.parse("2026-09-27T23:45:02Z"),
                evidenceIds);
        BunchingRootCauseDiagnosis diagnosis = new BunchingRootCauseDiagnosis(
                UUID.fromString("f2b8d1b5-c24c-48bb-8a66-bff7be1e9e73"),
                "A vehicle breakdown delayed the leader.",
                new BigDecimal("0.82"),
                List.of("diagnosis-evidence-1"),
                List.of("Synthetic test diagnosis."));
        ValidatedMitigationContext context = new ValidatedMitigationContext(
                "SBS5898U",
                "44241",
                FollowingBusState.READY_TO_DEPART,
                120,
                75,
                CrowdSafetyStatus.PASSED,
                MitigationValidationStatus.VALIDATED,
                List.of("operations-evidence-1"));
        return new MitigationCandidateRequest(SourceClassification.SIMULATION, event, diagnosis, context);
    }
}
