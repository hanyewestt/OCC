package com.csd.occ.planning.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.csd.occ.planning.diagnosis.infrastructure.config.DiagnosisGeminiProperties;
import com.csd.occ.planning.diagnosis.infrastructure.config.DiagnosisProperties;
import com.csd.occ.planning.diagnosis.infrastructure.config.GeminiDiagnosisConfig;
import com.csd.occ.planning.diagnosis.infrastructure.gemini.GeminiRootCauseModelAdapter;
import com.csd.occ.planning.recommendation.infrastructure.config.GeminiRecommendationConfig;
import com.csd.occ.planning.recommendation.infrastructure.config.RecommendationGeminiProperties;
import com.csd.occ.planning.recommendation.infrastructure.config.RecommendationInputProperties;
import com.csd.occ.planning.recommendation.infrastructure.gemini.GeminiMitigationCandidateAdapter;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

class GeminiClientWiringTest {
    @Test
    void diagnosisAndRecommendationUseTheirOwnClientsWhenBothAreEnabled() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
                    Map.of("occ.recommendation.ai.enabled", "true")));
            context.registerBean(GeminiEndpointProperties.class, () -> new GeminiEndpointProperties(""));
            context.registerBean(DiagnosisGeminiProperties.class, () -> new DiagnosisGeminiProperties(
                    "synthetic-diagnosis-key", "diagnosis-model", 1.0, GoogleGenAiThinkingLevel.MINIMAL,
                    1024, Duration.ofSeconds(30), 2));
            context.registerBean(RecommendationGeminiProperties.class, () -> new RecommendationGeminiProperties(
                    "synthetic-recommendation-key", "recommendation-model", 1.0, 512,
                    GoogleGenAiThinkingLevel.MINIMAL, Duration.ofSeconds(30), 2));
            context.registerBean(DiagnosisProperties.class, () -> new DiagnosisProperties(
                    URI.create("http://disruption:8002"), URI.create("http://ingestion:8001"), false,
                    Duration.ofSeconds(2), 10, Duration.ofMinutes(5), 3, 0, Duration.ofSeconds(2),
                    Duration.ofMinutes(1), 1, Duration.ofMinutes(15), 2.0, 0.7, Duration.ofSeconds(10),
                    131072, 65536));
            context.registerBean(RecommendationInputProperties.class, () -> new RecommendationInputProperties(65536));
            context.registerBean("diagnosisAudit", com.csd.occ.planning.diagnosis.infrastructure.config.AiAuditProperties.class,
                    () -> new com.csd.occ.planning.diagnosis.infrastructure.config.AiAuditProperties(65536));
            context.registerBean("recommendationAudit", com.csd.occ.planning.recommendation.infrastructure.config.AiAuditProperties.class,
                    () -> new com.csd.occ.planning.recommendation.infrastructure.config.AiAuditProperties(65536));
            context.registerBean(JsonMapper.class, () -> JsonMapper.builder().findAndAddModules().build());
            context.registerBean(Clock.class, Clock::systemUTC);
            context.register(GeminiDiagnosisConfig.class, GeminiRecommendationConfig.class,
                    GeminiRootCauseModelAdapter.class);
            context.refresh();

            assertThat(ReflectionTestUtils.getField(context.getBean(GeminiRootCauseModelAdapter.class), "chatClient"))
                    .isSameAs(context.getBean("diagnosisChatClient", ChatClient.class));
            assertThat(ReflectionTestUtils.getField(context.getBean(GeminiMitigationCandidateAdapter.class), "chatClient"))
                    .isSameAs(context.getBean("recommendationGeminiChatClient", ChatClient.class));
            assertThat(context.getBean("diagnosisGeminiChatModel", GoogleGenAiChatModel.class)
                    .getOptions().getModel()).isEqualTo("diagnosis-model");
            assertThat(context.getBean("recommendationGeminiChatModel", GoogleGenAiChatModel.class)
                    .getOptions().getModel()).isEqualTo("recommendation-model");
        }
    }
}
