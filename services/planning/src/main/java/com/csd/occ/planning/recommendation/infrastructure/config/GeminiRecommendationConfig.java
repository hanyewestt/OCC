package com.csd.occ.planning.recommendation.infrastructure.config;

import com.csd.occ.planning.recommendation.infrastructure.config.RecommendationInputProperties;
import com.csd.occ.planning.recommendation.infrastructure.gemini.GeminiMitigationCandidateAdapter;
import com.csd.occ.planning.infrastructure.config.GeminiEndpointProperties;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import java.time.Clock;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "occ.recommendation.ai", name = "enabled", havingValue = "true")
public class GeminiRecommendationConfig {

    @Bean(destroyMethod = "close")
    Client recommendationGeminiClient(
            RecommendationGeminiProperties properties, GeminiEndpointProperties endpointProperties) {
        HttpRetryOptions retryOptions = HttpRetryOptions.builder()
                .attempts(properties.retryAttempts())
                .build();
        HttpOptions.Builder httpBuilder = HttpOptions.builder()
                .timeout(properties.requestTimeoutMillis())
                .retryOptions(retryOptions);
        if (endpointProperties.isConfigured()) httpBuilder.baseUrl(endpointProperties.baseUrl());
        return Client.builder()
                .apiKey(properties.requiredApiKey())
                .httpOptions(httpBuilder.build())
                .build();
    }

    @Bean
    GoogleGenAiChatModel recommendationGeminiChatModel(
            @Qualifier("recommendationGeminiClient") Client recommendationGeminiClient,
            RecommendationGeminiProperties properties) {
        GoogleGenAiChatOptions options = GoogleGenAiChatOptions.builder()
                .model(properties.model())
                .temperature(properties.temperature())
                .maxOutputTokens(properties.maxOutputTokens())
                .thinkingLevel(properties.thinkingLevel())
                .includeThoughts(false)
                .candidateCount(1)
                .build();
        return GoogleGenAiChatModel.builder()
                .genAiClient(recommendationGeminiClient)
                .options(options)
                .build();
    }

    @Bean
    ChatClient recommendationGeminiChatClient(
            @Qualifier("recommendationGeminiChatModel") GoogleGenAiChatModel recommendationGeminiChatModel) {
        return ChatClient.create(recommendationGeminiChatModel);
    }

    @Bean
    GeminiMitigationCandidateAdapter geminiMitigationCandidateAdapter(
            @Qualifier("recommendationGeminiChatClient") ChatClient recommendationGeminiChatClient,
            JsonMapper json,
            Clock clock,
            RecommendationGeminiProperties properties,
            RecommendationInputProperties inputProperties, AiAuditProperties audit) {
        return new GeminiMitigationCandidateAdapter(
                recommendationGeminiChatClient,
                json,
                clock,
                properties.model(),
                inputProperties, audit);
    }
}
