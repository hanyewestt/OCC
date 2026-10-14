package com.csd.occ.planning.diagnosis.infrastructure.gemini;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.csd.occ.planning.diagnosis.domain.*;
import com.csd.occ.planning.diagnosis.infrastructure.config.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import tools.jackson.databind.json.JsonMapper;
class GeminiRootCauseModelAdapterTest {
    private GeminiRootCauseModelAdapter adapter(ChatModel model) {
        var settings = mock(DiagnosisProperties.class);
        when(settings.maxPromptInputBytes()).thenReturn(65536L);
        var gemini = mock(DiagnosisGeminiProperties.class);
        when(gemini.model()).thenReturn("synthetic-model");
        return new GeminiRootCauseModelAdapter(ChatClient.create(model), JsonMapper.builder().findAndAddModules().build(),
                Clock.fixed(Instant.parse("2026-10-13T00:00:00Z"), ZoneOffset.UTC), gemini, settings, new AiAuditProperties(65536));
    }
    private ChatModel model(String content) {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(GoogleGenAiChatOptions.builder().model("synthetic-model").build());
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(content)))));
        return model;
    }
    @Test void validResponseAndRepairFeedbackAreRetained() {
        String response = "{\"causeCode\":\"UNKNOWN\",\"likelyCause\":\"Insufficient evidence\",\"supportingEvidenceIds\":[],\"limitations\":[]}";
        var result = adapter(model(response)).diagnose(mock(EvidenceSnapshot.class), List.of("Correct the cause code"), 2);
        assertThat(result.invocation().generatedResponse()).isEqualTo(response);
        assertThat(result.invocation().inputSnapshot()).contains("Correct the cause code");
        assertThat(result.invocation().attemptNumber()).isEqualTo(2);
    }
    @Test void malformedResponseIsPreservedForRepairAndFailureAudit() {
        var failure = catchThrowableOfType(ModelInvocationFailureException.class,
                () -> adapter(model("{invalid")).diagnose(mock(EvidenceSnapshot.class), List.of(), 1));
        assertThat(failure.invocationDetails().generatedResponse()).isEqualTo("{invalid");
        assertThat(failure.invocationDetails().inputSnapshot()).isNotNull();
        assertThat(failure.invocationDetails().validationOutcome()).isEqualTo("INVALID");
    }
    @Test void responseCaptureIsBoundedAndMarksTruncation() {
        assertThat(new AiAuditProperties(3).capture("abcdef")).isEqualTo("abc\n[TRUNCATED]");
        assertThat(new AiAuditProperties(3).capture(null)).isNull();
    }
}
