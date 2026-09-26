package com.csd.occ.planning.recommendationengine.infrastructure.config;

import com.csd.occ.planning.recommendationengine.infrastructure.gemini.GeminiMitigationCandidateAdapter;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "occ.recommendation.ai", name = "enabled", havingValue = "true")
public class GeminiRecommendationConfig {

    @Bean(destroyMethod = "close")
    Client recommendationGeminiClient(RecommendationGeminiProperties properties) {
        HttpRetryOptions retryOptions = HttpRetryOptions.builder()
                .attempts(properties.retryAttempts())
                .build();
        HttpOptions httpOptions = HttpOptions.builder()
                .timeout(properties.requestTimeoutMillis())
                .retryOptions(retryOptions)
                .build();
        return Client.builder()
                .apiKey(properties.requiredApiKey())
                .httpOptions(httpOptions)
                .build();
    }

    @Bean
    GoogleGenAiChatModel recommendationGeminiChatModel(
            Client recommendationGeminiClient,
            RecommendationGeminiProperties properties) {
        GoogleGenAiChatOptions options = GoogleGenAiChatOptions.builder()
                .model(properties.model())
                .temperature(properties.temperature())
                .maxOutputTokens(properties.maxOutputTokens())
                .candidateCount(1)
                .build();
        return GoogleGenAiChatModel.builder()
                .genAiClient(recommendationGeminiClient)
                .options(options)
                .build();
    }

    @Bean
    ChatClient recommendationGeminiChatClient(GoogleGenAiChatModel recommendationGeminiChatModel) {
        return ChatClient.create(recommendationGeminiChatModel);
    }

    @Bean
    GeminiMitigationCandidateAdapter geminiMitigationCandidateAdapter(
            ChatClient recommendationGeminiChatClient,
            JsonMapper json) {
        return new GeminiMitigationCandidateAdapter(recommendationGeminiChatClient, json);
    }
}
