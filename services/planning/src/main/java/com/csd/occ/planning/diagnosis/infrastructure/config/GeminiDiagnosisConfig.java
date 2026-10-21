package com.csd.occ.planning.diagnosis.infrastructure.config;

import com.csd.occ.planning.infrastructure.config.GeminiEndpointProperties;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class GeminiDiagnosisConfig {
    @Bean(destroyMethod = "close")
    Client diagnosisGeminiClient(DiagnosisGeminiProperties properties, GeminiEndpointProperties endpointProperties) {
        HttpRetryOptions retry = HttpRetryOptions.builder().attempts(properties.retryAttempts()).build();
        HttpOptions.Builder httpBuilder = HttpOptions.builder()
                .timeout(properties.requestTimeoutMillis())
                .retryOptions(retry);
        if (endpointProperties.isConfigured()) httpBuilder.baseUrl(endpointProperties.baseUrl());
        HttpOptions http = httpBuilder.build();
        return Client.builder().apiKey(properties.requiredApiKey()).httpOptions(http).build();
    }

    @Bean
    GoogleGenAiChatModel diagnosisGeminiChatModel(@Qualifier("diagnosisGeminiClient") Client diagnosisGeminiClient,
            DiagnosisGeminiProperties properties) {
        GoogleGenAiChatOptions options = GoogleGenAiChatOptions.builder().model(properties.model())
                .temperature(properties.temperature()).thinkingLevel(properties.thinkingLevel())
                .maxOutputTokens(properties.maxOutputTokens()).includeThoughts(false).candidateCount(1).build();
        return GoogleGenAiChatModel.builder().genAiClient(diagnosisGeminiClient).options(options).build();
    }

    @Bean
    ChatClient diagnosisChatClient(@Qualifier("diagnosisGeminiChatModel") GoogleGenAiChatModel diagnosisGeminiChatModel) {
        return ChatClient.create(diagnosisGeminiChatModel);
    }
}
