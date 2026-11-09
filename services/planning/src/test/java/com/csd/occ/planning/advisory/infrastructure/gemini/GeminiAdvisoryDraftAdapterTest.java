package com.csd.occ.planning.advisory.infrastructure.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.csd.occ.planning.advisory.domain.AdvisoryContextPort;
import com.csd.occ.planning.advisory.domain.HazardEventFacts;
import com.csd.occ.planning.recommendation.infrastructure.config.RecommendationAiProperties;
import com.csd.occ.planning.recommendation.infrastructure.config.RecommendationInputProperties;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.json.JsonMapper;

class GeminiAdvisoryDraftAdapterTest {
    private static final String VALID = """
            {"title":"Flood update","message":"Water reported. Use caution.","services":["190"],"locations":["Example Stop"]}
            """;

    @Test
    void disabledAiUsesSourceFactsAndDistinctAffectedServicesAndStopsWithoutCallingTheModel() {
        var model = mock(ChatModel.class);
        var suggestion = adapter(model, false).suggestNotice(hazard(true, "Water reported."));
        assertThat(suggestion.title()).isEqualTo("Flood alert");
        assertThat(suggestion.message()).isEqualTo("Water reported.\nUse caution.");
        assertThat(suggestion.services()).containsExactly("190");
        assertThat(suggestion.locations()).containsExactly("Example Stop");
        assertThat(suggestion.generator()).isEqualTo("SOURCE_FACTS");
        verifyNoInteractions(model);
    }

    @Test
    void sourceLocationsRemainAvailableWithoutFleetExposure() {
        var model = mock(ChatModel.class);
        var suggestion = adapter(model, false).suggestNotice(hazard(false, "Water reported."));
        assertThat(suggestion.services()).isEmpty();
        assertThat(suggestion.locations()).containsExactly("Reported Road");
        assertThat(suggestion.message()).contains("Use caution.");
        verifyNoInteractions(model);
    }

    @Test
    void enabledAiReturnsEditableTextAndReceivesOnlyPassengerFacts() {
        var model = modelReturning(VALID);
        var source = hazard(true, "Water reported.");
        var suggestion = adapter(model, true).suggestNotice(source);
        assertThat(suggestion.title()).isEqualTo("Flood update");
        assertThat(suggestion.message()).isEqualTo("Water reported. Use caution.");
        assertThat(suggestion.services()).containsExactly("190");
        assertThat(suggestion.locations()).containsExactly("Example Stop");
        assertThat(suggestion.generator()).isEqualTo("synthetic-model");
        var prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        assertThat(prompt.getValue().getUserMessage().getText())
                .contains("Water reported.", "Use caution.", "190", "Example Stop")
                .doesNotContain(source.disruptionId().toString(), source.sourceObservationId().toString(),
                        "private-bus-id", "private-source-reference");
        assertThat(prompt.getValue().getOptions()).isInstanceOfSatisfying(GoogleGenAiChatOptions.class,
                options -> assertThat(options.getResponseMimeType()).isEqualTo("application/json"));
    }

    @ParameterizedTest
    @MethodSource("invalidSuggestions")
    void unsupportedModelOutputCannotBecomePassengerWording(String output) {
        assertThatThrownBy(() -> adapter(modelReturning(output), true).suggestNotice(hazard(true, "Water reported.")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("supported source facts and restrictions");
    }

    static Stream<String> invalidSuggestions() {
        return Stream.of("null", VALID.replace("Flood update", " "), VALID.replace("Flood update", "x".repeat(161)),
                VALID.replace("Water reported. Use caution.", "x".repeat(2001)),
                VALID.replace("Use caution.", "Safe to travel."), VALID.replace("190", "999"),
                VALID.replace("Example Stop", "Invented Stop"), VALID.replace("[\"190\"]", "null"),
                VALID.replace("[\"Example Stop\"]", "null"));
    }

    @Test
    void malformedOrEmptyModelResponsesAreNotAccepted() {
        assertThatThrownBy(() -> adapter(modelReturning("{not-json"), true).suggestNotice(hazard(true, "Water reported.")))
                .isInstanceOf(tools.jackson.core.JacksonException.class);
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of()));
        assertThatThrownBy(() -> adapter(model, true).suggestNotice(hazard(true, "Water reported.")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Empty notice model response");
    }

    @Test
    void oversizedSourceIsRejectedBeforeSendingAnythingToTheModel() {
        var model = mock(ChatModel.class);
        assertThatThrownBy(() -> adapter(model, true).suggestNotice(hazard(true, "水".repeat(4000))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("input exceeds");
        verify(model, never()).call(any(Prompt.class));
    }

    @Test
    void failedProviderCallDoesNotFabricateASuggestion() {
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("Synthetic provider unavailable"));
        assertThatThrownBy(() -> adapter(model, true).suggestNotice(hazard(true, "Water reported.")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("provider unavailable");
    }

    private GeminiAdvisoryDraftAdapter adapter(ChatModel model, boolean enabled) {
        var clients = new StaticListableBeanFactory();
        if (enabled) {
            when(model.getOptions()).thenReturn(GoogleGenAiChatOptions.builder().model("synthetic-model").build());
            clients.addBean("model", ChatClient.create(model));
        }
        return new GeminiAdvisoryDraftAdapter(clients.getBeanProvider(ChatClient.class),
                new RecommendationAiProperties(enabled), new RecommendationInputProperties(8192), JsonMapper.builder().build());
    }

    private ChatModel modelReturning(String output) {
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(output))),
                ChatResponseMetadata.builder().model("synthetic-model").build()));
        return model;
    }

    private AdvisoryContextPort.Hazard hazard(boolean exposed, String description) {
        var facts = new HazardEventFacts.Fact("FLOOD", "private-source-reference", "OBSERVED", Instant.EPOCH,
                null, null, "Flood alert", description, "Severe", "Use caution.", List.of(), null, null, null, null,
                List.of(new HazardEventFacts.Location("ROAD", "private-location-id", "Reported Road", null, null, null, null, null)));
        var exposure = new AdvisoryContextPort.Exposure("private-bus-id", "190", 1, "44251", "Example Stop", 10, "SYNTHETIC", "Unverified");
        return new AdvisoryContextPort.Hazard(UUID.randomUUID(), "FLOOD", "ACTIVE", null, "OBSERVATION_ONLY", null,
                UUID.randomUUID(), Instant.EPOCH, facts, exposed ? List.of(exposure, exposure) : List.of(), List.of());
    }
}
