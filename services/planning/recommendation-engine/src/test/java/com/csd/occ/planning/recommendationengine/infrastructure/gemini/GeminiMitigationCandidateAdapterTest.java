package com.csd.occ.planning.recommendationengine.infrastructure.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.csd.occ.planning.recommendationengine.domain.BunchingEventStatus;
import com.csd.occ.planning.recommendationengine.domain.BunchingRootCauseDiagnosis;
import com.csd.occ.planning.recommendationengine.domain.CandidateMitigationType;
import com.csd.occ.planning.recommendationengine.domain.ConfirmedBunchingEvent;
import com.csd.occ.planning.recommendationengine.domain.CrowdSafetyStatus;
import com.csd.occ.planning.recommendationengine.domain.FollowingBusState;
import com.csd.occ.planning.recommendationengine.domain.MitigationCandidateRequest;
import com.csd.occ.planning.recommendationengine.domain.MitigationValidationStatus;
import com.csd.occ.planning.recommendationengine.domain.SourceClassification;
import com.csd.occ.planning.recommendationengine.domain.ValidatedMitigationContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
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

        var candidate = adapter.generate(request());

        assertThat(candidate).hasValueSatisfying(value -> {
            assertThat(value.mitigationType()).isEqualTo(CandidateMitigationType.HOLD);
            assertThat(value.targetBusReference()).isEqualTo("SBS5898U");
            assertThat(value.controlStopCode()).isEqualTo("44241");
            assertThat(value.requestedAdjustmentSeconds()).isEqualTo(120);
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
    void semanticallyInvalidStructuredOutputIsRejectedByDomainContract() {
        ChatModel model = modelReturning("""
                {
                  "mitigationType": "HOLD",
                  "targetBusReference": "SBS5898U",
                  "controlStopCode": "44241",
                  "requestedAdjustmentSeconds": 0,
                  "rationale": "Invalid zero-second adjustment."
                }
                """);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> adapter(model).generate(request()))
                .withMessageContaining("requestedAdjustmentSeconds");
    }

    private GeminiMitigationCandidateAdapter adapter(ChatModel model) {
        return new GeminiMitigationCandidateAdapter(
                ChatClient.create(model),
                JsonMapper.builder().findAndAddModules().build());
    }

    private ChatModel modelReturning(String content) {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(GoogleGenAiChatOptions.builder()
                .model("gemini-2.5-flash")
                .build());
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage(content)))));
        return model;
    }

    private MitigationCandidateRequest request() {
        ConfirmedBunchingEvent event = new ConfirmedBunchingEvent(
                UUID.fromString("c5934f15-4a65-4e94-87ea-a5a64a7dd4c1"),
                "190",
                1,
                "44251",
                BunchingEventStatus.CONFIRMED,
                600,
                List.of(276, 221),
                Instant.parse("2026-09-27T23:45:02Z"),
                List.of("arrival-observation-1", "arrival-observation-2"));
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
